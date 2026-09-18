package dev.pranav.applock.core.network

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository

/**
 * Sets the phone's screen timeout to follow the trusted networks. Near one, the timeout is the
 * longer one chosen for trusted Wi-Fi; everywhere else it is the shorter one. A phone taken while
 * unlocked away from home then locks itself sooner.
 *
 * Android stores the timeout, and it outlasts the app, but trust lives only in memory. So this fails
 * closed. Whenever [TrustedNetworkMonitor] refreshes or trust changes, it writes the value for the
 * current state. That includes every app start, when trust is still false. Turning the option off
 * writes the shorter value.
 *
 * Writing needs "Modify system settings". Without it nothing is written, and the log says so.
 */
object ScreenTimeoutByNetwork {
    private const val TAG = "ScreenTimeoutByNetwork"

    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /** Writes the timeout for [trusted] while the option is on; does nothing while it's off. */
    fun apply(context: Context, trusted: Boolean) {
        val repository = context.appLockRepository()
        if (!repository.isScreenTimeoutByNetworkEnabled()) return

        if (trusted) {
            write(context, repository.getScreenTimeoutTrustedSeconds(), "on a trusted network")
        } else {
            write(context, repository.getScreenTimeoutAwaySeconds(), "not on a trusted network")
        }
    }

    /** Writes the shorter timeout whether the option is on or not, for when it's turned off. */
    fun applyAway(context: Context) {
        write(context, context.appLockRepository().getScreenTimeoutAwaySeconds(), "option turned off")
    }

    /**
     * Opens Android's "Modify system settings" switch for this app, or the list of apps where a phone
     * lacks the direct page.
     */
    fun openPermissionPage(context: Context) {
        val listPage = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        val appPage = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            "package:${context.packageName}".toUri()
        )
        try {
            try {
                context.startActivity(appPage)
            } catch (_: ActivityNotFoundException) {
                context.startActivity(listPage)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Couldn't open the Modify system settings page", e)
        }
    }

    private fun write(context: Context, seconds: Int, reason: String) {
        val resolver = context.contentResolver
        val millis = seconds * 1000
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        if (current == millis) {
            LogUtils.d(TAG, "Screen timeout already $seconds s ($reason)")
            return
        }

        if (!canWrite(context)) {
            LogUtils.d(
                TAG,
                "Can't set the screen timeout to $seconds s ($reason): " +
                        "Modify system settings not allowed"
            )
            return
        }

        try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, millis)
            val previous = if (current < 0) "unknown" else "${current / 1000} s"
            // Android recounts the time to the screen going off when this changes, and with it the
            // dim that leads up to it. Written while the screen is on, that shows: a screen part way
            // through dimming comes back to full and dims again, which looks like a flicker.
            val whileOn = if (context.getSystemService(PowerManager::class.java)?.isInteractive == true) {
                ", while the screen was on, so its dim countdown restarts"
            } else {
                ""
            }
            LogUtils.d(TAG, "Screen timeout set to $seconds s, was $previous ($reason)$whileOn")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to set the screen timeout to $seconds s ($reason)", e)
        }
    }
}
