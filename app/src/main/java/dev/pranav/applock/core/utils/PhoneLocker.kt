package dev.pranav.applock.core.utils

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.services.AppLockAccessibilityService

/**
 * Locks the phone for the anti-uninstall guards, so every guard locks it the same way.
 *
 * The accessibility service's lock action comes first: it needs no admin policy, but only works
 * while Android has the service connected, and Android drops that connection before it tells the
 * service it is being turned off. [DevicePolicyManager.lockNow] is the fallback: it needs no
 * accessibility service, but only works while our admin is active and holds force-lock.
 */
object PhoneLocker {
    private const val TAG = "PhoneLocker"

    /** Returns whether a lock went through. [reason] only goes to the log. */
    @SuppressLint("InlinedApi")
    fun lockPhone(context: Context, reason: String): Boolean {
        val service = AppLockAccessibilityService.connectedInstance
        if (service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true) {
            LogUtils.d(TAG, "Locked the phone through accessibility: $reason")
            return true
        }

        val dpm = context.getSystemService<DevicePolicyManager>()
        if (dpm != null && DeviceAdmin.hasForceLock(context)) {
            try {
                dpm.lockNow()
                LogUtils.d(TAG, "Locked the phone through device admin: $reason")
                return true
            } catch (e: SecurityException) {
                LogUtils.e(TAG, "Device admin refused to lock the phone: $reason", e)
            }
        }

        LogUtils.e(TAG, "Could not lock the phone: $reason")
        return false
    }
}
