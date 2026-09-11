package dev.pranav.applock.features.lockscreen.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pranav.applock.R
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.AppLockRepository
import kotlinx.coroutines.delay

/**
 * Whole seconds left before another unlock attempt is allowed, or 0 when one is allowed now.
 *
 * Re-read a few times a second while shown, so a wait started by a wrong try - on this screen or
 * another - shows up at once and ends on time. The caller only recomposes when the second changes.
 */
@Composable
fun rememberLockoutSeconds(): Long {
    val appLockRepository = LocalContext.current.appLockRepository()
    val seconds by produceState(initialValue = appLockRepository.lockoutSeconds()) {
        while (true) {
            value = appLockRepository.lockoutSeconds()
            delay(LOCKOUT_POLL_INTERVAL_MS)
        }
    }
    return seconds
}

/** Whole seconds left on the wait, rounded up so the countdown never shows 0:00 while it runs. */
fun AppLockRepository.lockoutSeconds(): Long = (getLockoutRemainingMillis() + 999L) / 1000L

/** "Too many attempts. Try again in 0:30", for a wait with [seconds] left. */
@Composable
fun lockoutMessage(seconds: Long): String =
    stringResource(R.string.too_many_attempts_try_again_in, DateUtils.formatElapsedTime(seconds))

/** [lockoutMessage] outside composition, e.g. for a Toast. */
fun Context.getLockoutMessage(seconds: Long): String =
    getString(R.string.too_many_attempts_try_again_in, DateUtils.formatElapsedTime(seconds))

private const val LOCKOUT_POLL_INTERVAL_MS = 250L
