package dev.pranav.applock.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit

/**
 * Makes the user wait after too many wrong PINs, patterns or passwords, so the lock can't be guessed
 * from the screen. Every check goes through [PreferencesRepository], so the lock screens, the app's
 * own screen, turning anti-uninstall off and changing the PIN all share one count.
 *
 * The count is stored rather than kept in memory, so closing the lock screen or killing the app
 * doesn't reset it. Wrong tries are forgiven only by a correct entry or a strong biometric.
 */
class UnlockAttemptLimiter(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * How long until another attempt is allowed, or 0 if one is allowed now.
     *
     * The deadline is on [SystemClock.elapsedRealtime], which changing the time in Settings can't
     * move. That clock starts over on a reboot, so a wait set before one is started again in full.
     */
    fun remainingMillis(): Long {
        synchronized(LOCK) {
            val deadline = prefs.getLong(KEY_DEADLINE, 0L)
            if (deadline == 0L) return 0L

            val now = SystemClock.elapsedRealtime()
            val fullDelay = delayFor(prefs.getInt(KEY_FAILURES, 0))
            if (prefs.getInt(KEY_BOOT_COUNT, -1) != bootCount() || deadline - now > fullDelay) {
                prefs.edit(commit = true) {
                    putLong(KEY_DEADLINE, now + fullDelay)
                    putInt(KEY_BOOT_COUNT, bootCount())
                }
                return fullDelay
            }

            return (deadline - now).coerceAtLeast(0L)
        }
    }

    /** Counts a wrong entry, starting or lengthening the wait once the free tries are used up. */
    fun recordFailure() {
        synchronized(LOCK) {
            val failures = prefs.getInt(KEY_FAILURES, 0) + 1
            val delay = delayFor(failures)
            prefs.edit(commit = true) {
                putInt(KEY_FAILURES, failures)
                if (delay > 0L) {
                    putLong(KEY_DEADLINE, SystemClock.elapsedRealtime() + delay)
                    putInt(KEY_BOOT_COUNT, bootCount())
                }
            }
        }
    }

    /** Forgets every wrong entry and ends any wait. Writes only when there is something to forget. */
    fun recordSuccess() {
        synchronized(LOCK) {
            if (!prefs.contains(KEY_FAILURES) && !prefs.contains(KEY_DEADLINE)) return
            prefs.edit(commit = true) {
                remove(KEY_FAILURES)
                remove(KEY_DEADLINE)
                remove(KEY_BOOT_COUNT)
            }
        }
    }

    private fun bootCount(): Int {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, 0)
        } catch (_: Exception) {
            // Without it, a reboot is still caught by the deadline lying further ahead than a full wait.
            0
        }
    }

    companion object {
        private const val PREFS_NAME = "app_lock_prefs"
        private const val KEY_FAILURES = "failed_unlock_attempts"
        private const val KEY_DEADLINE = "lockout_deadline"
        private const val KEY_BOOT_COUNT = "lockout_boot_count"

        private const val FREE_ATTEMPTS = 4
        private const val FIRST_DELAY_MS = 30_000L
        private const val MAX_DELAY_MS = 30 * 60_000L

        /** Each repository creates its own limiter, so they share one lock around the count. */
        private val LOCK = Any()

        /** 30s after the 5th wrong try, doubling with each one after it, up to 30 min. */
        private fun delayFor(failures: Int): Long {
            if (failures <= FREE_ATTEMPTS) return 0L
            val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(6)
            return (FIRST_DELAY_MS shl doublings).coerceAtMost(MAX_DELAY_MS)
        }
    }
}
