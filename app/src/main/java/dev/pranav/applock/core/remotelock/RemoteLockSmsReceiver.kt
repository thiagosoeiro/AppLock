package dev.pranav.applock.core.remotelock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The remote lock's SMS channel. It reads plain SMS as they arrive, even when the messaging app is
 * muted and shows nothing. RCS chats never come through here; [RemoteLockListener] sees those.
 *
 * Only the phone's SMS system can send it SMS_RECEIVED, since the manifest requires BROADCAST_SMS,
 * and the component stays disabled until remote lock is turned on. Nothing read here is stored or
 * logged.
 */
class RemoteLockSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val repository = context.appLockRepository()
            if (!repository.isRemoteLockEnabled()) return
            val keyword = repository.getRemoteLockKeyword() ?: return
            if (!RemoteLock.matches(readBody(intent), keyword)) return

            val email = RemoteLock.onKeywordMessage(
                context,
                System.currentTimeMillis(),
                RemoteLock.Source.Sms
            ) ?: return
            keepAliveUntilDone(email)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Checking an SMS failed", e)
        }
    }

    /**
     * Keeps the app running while the confirmation email is queued, for up to [WAIT_LIMIT_MS], since
     * Android may stop an app soon after its receiver returns. A slower email carries on regardless
     * for as long as the app runs.
     */
    private fun keepAliveUntilDone(email: Job) {
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val finish = {
            if (finished.compareAndSet(false, true)) pending.finish()
        }
        email.invokeOnCompletion { finish() }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, WAIT_LIMIT_MS)
    }

    /** The SMS text, with the parts of a long message joined back together. */
    private fun readBody(intent: Intent): String? {
        @Suppress("DEPRECATION") // There is no typed getter for the PDU array.
        val pdus = intent.extras?.get(EXTRA_PDUS) as? Array<*> ?: return null
        val format = intent.getStringExtra(EXTRA_FORMAT)
        return pdus.filterIsInstance<ByteArray>()
            .mapNotNull { SmsMessage.createFromPdu(it, format)?.messageBody }
            .joinToString("")
            .ifEmpty { null }
    }

    companion object {
        private const val TAG = "RemoteLockSmsReceiver"

        private const val EXTRA_PDUS = "pdus"
        private const val EXTRA_FORMAT = "format"

        // Well inside the time Android gives a receiver before it counts it as stuck.
        private const val WAIT_LIMIT_MS = 8_000L

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
                    PackageManager.PERMISSION_GRANTED

        /**
         * Adds this receiver to the app's surface while remote lock is on and removes it otherwise,
         * rather than leaving it reachable and ignoring what it receives.
         */
        fun setComponentEnabled(context: Context, enabled: Boolean) {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, RemoteLockSmsReceiver::class.java),
                    state,
                    PackageManager.DONT_KILL_APP
                )
                LogUtils.d(TAG, "SMS receiver enabled: $enabled")
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to set the SMS receiver's state", e)
            }
        }
    }
}
