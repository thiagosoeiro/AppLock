package dev.pranav.applock.core.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.core.utils.exec

/**
 * Shows notification content on the lock screen near a trusted network and hides it everywhere
 * else. A locked phone away from home then shows that notifications arrived, but not what they say
 * or any codes in them.
 *
 * Android stores the choice, and it outlasts the app, but trust lives only in memory. So this fails
 * closed. Whenever [TrustedNetworkMonitor] refreshes or trust changes, it writes the value for the
 * current state. That includes every app start, when trust is still false. Turning the option off
 * hides content.
 *
 * Writing needs WRITE_SECURE_SETTINGS, which only Shizuku or ADB can grant. Without it nothing is
 * written, and the log says so.
 */
object LockScreenContentByNetwork {
    private const val TAG = "LockScreenContentByNetwork"

    /** Android's hidden `Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS`: 1 shows content, 0 hides it. */
    private const val SETTING = "lock_screen_allow_private_notifications"

    private const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS

    fun canWrite(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Shows or hides content for [trusted] while the option is on; does nothing while it's off. */
    fun apply(context: Context, trusted: Boolean) {
        if (!context.appLockRepository().isLockScreenContentByNetworkEnabled()) return

        if (trusted) {
            write(context, show = true, reason = "on a trusted network")
        } else {
            write(context, show = false, reason = "not on a trusted network")
        }
    }

    /** Hides content whether the option is on or not, for when it's turned off. */
    fun applyHidden(context: Context) {
        write(context, show = false, reason = "option turned off")
    }

    /** The command that grants the permission from a computer with ADB. */
    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} $PERMISSION"

    /**
     * Grants the permission through Shizuku's shell, the same way ADB would. Needs Shizuku running
     * and allowed for this app. It waits for `pm` to finish, so call it off the main thread. Returns
     * whether the permission is held afterwards.
     */
    fun grantWithShizuku(context: Context): Boolean {
        if (canWrite(context)) return true

        try {
            exec("pm grant ${context.packageName} $PERMISSION")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to run the grant through Shizuku", e)
        }

        val granted = canWrite(context)
        LogUtils.d(
            TAG,
            if (granted) "WRITE_SECURE_SETTINGS granted through Shizuku"
            else "Shizuku didn't grant WRITE_SECURE_SETTINGS"
        )
        return granted
    }

    private fun write(context: Context, show: Boolean, reason: String) {
        val resolver = context.contentResolver
        val value = if (show) 1 else 0
        val state = if (show) "shown" else "hidden"
        val verb = if (show) "show" else "hide"
        // Readable by apps today; if a later Android stops sharing it, write anyway.
        val current = try {
            Settings.Secure.getInt(resolver, SETTING, -1)
        } catch (_: Exception) {
            -1
        }
        if (current == value) {
            LogUtils.d(TAG, "Lock-screen notification content already $state ($reason)")
            return
        }

        if (!canWrite(context)) {
            LogUtils.d(
                TAG,
                "Can't $verb lock-screen notification content ($reason): " +
                        "WRITE_SECURE_SETTINGS not granted"
            )
            return
        }

        try {
            Settings.Secure.putInt(resolver, SETTING, value)
            val previous = when (current) {
                1 -> "shown"
                0 -> "hidden"
                -1 -> "unknown"
                else -> "$current"
            }
            LogUtils.d(TAG, "Lock-screen notification content now $state, was $previous ($reason)")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to $verb lock-screen notification content ($reason)", e)
        }
    }
}
