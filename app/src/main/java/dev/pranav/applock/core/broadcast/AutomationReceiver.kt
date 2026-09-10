package dev.pranav.applock.core.broadcast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.SecurityUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.services.AppLockManager

/**
 * Lets automation apps (MacroDroid, Tasker, adb) set the global protection flag - the same flag
 * the shield toggle on the main screen writes, and the flag every backend checks before locking.
 *
 * Deliberately two setters rather than one toggle: an automation app cannot read our state, so a
 * toggle forces it to track state of its own, which silently drifts out of sync and leaves
 * protection off when you believe it is on. ENABLE and DISABLE are idempotent and cannot desync.
 *
 * Guarded three ways, since this component can switch a security feature off:
 *  - the component itself stays disabled until the user opts in from Settings,
 *  - every action requires the token generated there, compared in constant time,
 *  - anything unrecognised, unauthenticated, or sent before a token exists is ignored.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = context.appLockRepository()
        val action = intent.action

        if (!repository.isAutomationEnabled()) {
            reject(context, "automation control is off", action)
            return
        }

        val expected = repository.getAutomationToken()
        if (expected.isNullOrEmpty()) {
            reject(context, "no token has been generated", action)
            return
        }

        if (!SecurityUtils.constantTimeEquals(intent.getStringExtra(EXTRA_TOKEN), expected)) {
            reject(context, "invalid or missing token", action)
            return
        }

        when (action) {
            ACTION_ENABLE_PROTECTION -> setProtection(context, repository, true)
            ACTION_DISABLE_PROTECTION -> setProtection(context, repository, false)
            ACTION_QUERY_STATE -> LogUtils.d(TAG, "State queried via automation")
            else -> {
                reject(context, "unknown action", action)
                return
            }
        }

        val enabled = repository.isProtectEnabled()
        reportResult(if (enabled) RESULT_PROTECTION_ON else RESULT_PROTECTION_OFF, enabled.toString())
        notifyStateChanged(context, enabled)
    }

    private fun setProtection(context: Context, repository: AppLockRepository, enabled: Boolean) {
        repository.setProtectEnabled(enabled)

        if (enabled) {
            // Fail closed: an app unlocked while protection was off must not stay unlocked once
            // protection comes back, and the backend service may have been stopped meanwhile.
            AppLockManager.clearAllUnlockStates()
            try {
                AppLockServiceStarter.startAppropriateServices(context, repository)
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to start services after enabling protection", e)
            }
        }

        LogUtils.d(TAG, "Protection set to $enabled via automation")
    }

    private fun reject(context: Context, reason: String, action: String?) {
        LogUtils.e(TAG, "Rejected automation request '$action': $reason")
        reportResult(RESULT_REJECTED, reason)
    }

    /**
     * Ordered senders can read this back; plain [Context.sendBroadcast] senders cannot, and
     * calling these outside an ordered broadcast throws, hence the guard.
     */
    private fun reportResult(code: Int, data: String) {
        if (!isOrderedBroadcast) return
        resultCode = code
        resultData = data
    }

    companion object {
        private const val TAG = "AutomationReceiver"

        const val ACTION_ENABLE_PROTECTION = "dev.pranav.applock.action.ENABLE_PROTECTION"
        const val ACTION_DISABLE_PROTECTION = "dev.pranav.applock.action.DISABLE_PROTECTION"
        const val ACTION_QUERY_STATE = "dev.pranav.applock.action.QUERY_PROTECTION_STATE"

        /** Broadcast whenever protection changes, so automation can verify instead of assume. */
        const val ACTION_PROTECTION_STATE = "dev.pranav.applock.action.PROTECTION_STATE"

        const val EXTRA_TOKEN = "token"
        const val EXTRA_STATE = "state"

        const val RESULT_REJECTED = 0
        const val RESULT_PROTECTION_OFF = 1
        const val RESULT_PROTECTION_ON = 2

        /**
         * Announces the current protection state. Sent implicitly so automation apps can pick it
         * up with a dynamically registered receiver; it carries nothing but the on/off flag.
         */
        fun notifyStateChanged(context: Context, enabled: Boolean) {
            try {
                context.sendBroadcast(
                    Intent(ACTION_PROTECTION_STATE).putExtra(EXTRA_STATE, enabled)
                )
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to broadcast protection state", e)
            }
        }

        /**
         * Adds or removes this receiver from the app's exported surface entirely, rather than
         * leaving it reachable and merely ignoring what it receives.
         */
        fun setComponentEnabled(context: Context, enabled: Boolean) {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, AutomationReceiver::class.java),
                    state,
                    PackageManager.DONT_KILL_APP
                )
                LogUtils.d(TAG, "Automation receiver enabled: $enabled")
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to set automation receiver state", e)
            }
        }
    }
}
