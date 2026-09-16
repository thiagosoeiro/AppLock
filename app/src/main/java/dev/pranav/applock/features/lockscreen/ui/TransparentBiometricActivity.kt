package dev.pranav.applock.features.lockscreen.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.R
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme

/**
 * Raises the biometric prompt for [lockedPackage]. Callers keep whatever lock screen they are
 * showing on screen: [TransparentBiometricActivity] asks for it to be taken down only once it is
 * itself visible, so the locked app is never exposed in between.
 */
fun Context.startBiometricPrompt(lockedPackage: String, triggeringPackage: String? = null) {
    AppLockManager.reportBiometricAuthStarted()
    try {
        startActivity(Intent(this, TransparentBiometricActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra("locked_package", lockedPackage)
            putExtra("triggering_package", triggeringPackage)
        })
    } catch (e: Exception) {
        // The caller's lock screen is still up, so leaving it there is the safe outcome.
        Log.e("BiometricPrompt", "Could not start the biometric prompt", e)
        AppLockManager.reportBiometricAuthFinished()
    }
}

class TransparentBiometricActivity: FragmentActivity() {
    private val TAG = "TransparentBiometric"
    private var lockedPackageName: String? = null
    private var triggeringPackageName: String? = null

    private var isActivityResumed = false
    private var hasHiddenLockScreen = false
    private var isAuthenticated = false

    private var biometricPrompt: BiometricPrompt? = null

    // Set once this prompt's lock session is settled: authenticated, handed back to the lock screen,
    // or released after going away unanswered. Whatever comes later leaves the lock state alone.
    private var sessionEnded = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Set while this activity stays under a lock screen it handed back after a cancel, to see
    // whether the cancel was the user leaving. See [watchReturnedLockScreen].
    private var isWatchingReturnedLockScreen = false
    private var watchStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppLockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {}
            }
        }

        startAuthentication(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // This activity is singleInstance, so a re-launch reuses it and never runs onCreate again.
        startAuthentication(intent)
    }

    private fun startAuthentication(intent: Intent) {
        lockedPackageName = intent.getStringExtra("locked_package")
        triggeringPackageName = intent.getStringExtra("triggering_package")
        stopWatchingReturnedLockScreen()
        isAuthenticated = false
        hasHiddenLockScreen = false
        sessionEnded = false

        AppLockManager.reportBiometricAuthStarted()

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, authenticationCallback)
        biometricPrompt = prompt

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_verify_title))
            .setNegativeButtonText(getString(R.string.use_pin_button))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        try {
            prompt.authenticate(promptInfo)
            if (isActivityResumed) hideLockScreen()
        } catch (e: Exception) {
            Log.e(TAG, "Biometric failed to start", e)
            fallBackToLockScreen()
        }
    }

    private val authenticationCallback = object: BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            LogUtils.d(TAG, "Biometric authentication error: $errString ($errorCode)")
            fallBackToLockScreen(watchForLeaving = errorCode in CANCEL_ERRORS)
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            isAuthenticated = true
            sessionEnded = true
            appLockRepository().clearFailedAttempts()
            AppLockManager.reportBiometricAuthFinished()
            AppLockManager.isLockScreenShown.set(false)
            lockedPackageName?.let {
                AppLockManager.temporarilyUnlockAppWithBiometrics(it)
            }

            // The Accessibility service will detect the unlock state
            // and close the Service View automatically
            finish()
        }
    }

    /**
     * Takes the caller's lock screen down, now that this activity covers the locked app itself.
     */
    private fun hideLockScreen() {
        if (hasHiddenLockScreen) return
        hasHiddenLockScreen = true
        AppLockManager.lockScreenHost?.hideLockScreen()
    }

    /**
     * Hands control back to the lock screen after a failed or cancelled prompt.
     *
     * While this activity is still in front, the user dismissed the prompt themselves ("Use PIN" or
     * a cancelled dialog), so the lock screen goes back up - without auto-prompting again, or
     * cancelling would loop straight back into the prompt. If the foreground is already gone, the
     * prompt went away unanswered and [releaseUnansweredPrompt] settles it instead.
     *
     * A plain cancel can also come from leaving, so with [watchForLeaving] this activity stays under
     * the returned lock screen for a moment instead of finishing; see [watchReturnedLockScreen].
     */
    private fun fallBackToLockScreen(watchForLeaving: Boolean = false) {
        if (sessionEnded || isFinishing) return

        val host = AppLockManager.lockScreenHost
        val lockedPackage = lockedPackageName
        if (isActivityResumed && host != null && lockedPackage != null) {
            sessionEnded = true
            AppLockManager.reportBiometricAuthFinished()
            host.showLockScreen(
                packageName = lockedPackage,
                triggeringPackage = triggeringPackageName ?: "",
                autoPromptBiometrics = false
            )
            if (watchForLeaving) {
                watchReturnedLockScreen()
                return
            }
        } else {
            releaseUnansweredPrompt()
        }

        finish()
    }

    /**
     * Keeps this activity under the lock screen it just handed back, to catch a cancel that was
     * really the user leaving.
     *
     * One UI cancels the prompt as soon as Home or Recents is pressed, and gives this activity its
     * window focus back, all before the activity is stopped - to begin with, that looks exactly like
     * Back. The difference comes a moment later: leaving stops this activity (about 0.9 s after the
     * cancel for Home on the phone this was found on), while after Back it stays. So the activity
     * stays under the lock screen, where it can't be seen, for up to [RETURNED_LOCK_SCREEN_WATCH_MS].
     * If it is stopped in that time, [takeDownReturnedLockScreen] treats the prompt as one that went
     * away unanswered. The watch ends early once the lock screen is gone some other way.
     */
    private fun watchReturnedLockScreen() {
        isWatchingReturnedLockScreen = true
        watchStartedAt = SystemClock.elapsedRealtime()
        mainHandler.postDelayed(watchTick, WATCH_TICK_MS)
    }

    private val watchTick = object: Runnable {
        override fun run() {
            if (!isWatchingReturnedLockScreen) return
            val lockScreenGone = !AppLockManager.isLockScreenShown.get()
            val watchOver = SystemClock.elapsedRealtime() - watchStartedAt >= RETURNED_LOCK_SCREEN_WATCH_MS
            if (lockScreenGone || watchOver) {
                stopWatchingReturnedLockScreen()
                finish()
            } else {
                mainHandler.postDelayed(this, WATCH_TICK_MS)
            }
        }
    }

    private fun stopWatchingReturnedLockScreen() {
        isWatchingReturnedLockScreen = false
        mainHandler.removeCallbacks(watchTick)
    }

    /**
     * This activity was stopped while under a returned lock screen: the cancel was the user leaving
     * for Home, Recents or another app, or the screen going off. The lock screen is taken down and
     * the service told, as for any prompt that went away unanswered, so it isn't left over the home
     * screen and the interruption counts towards waiting for a tap.
     */
    private fun takeDownReturnedLockScreen() {
        stopWatchingReturnedLockScreen()
        // Cleared already if the lock screen was unlocked or closed on the way out.
        if (!AppLockManager.isLockScreenShown.compareAndSet(true, false)) return

        LogUtils.d(TAG, "Left the lock screen for $lockedPackageName right after its prompt was cancelled")
        val host = AppLockManager.lockScreenHost ?: return
        host.hideLockScreen()
        val lockedPackage = lockedPackageName ?: return
        host.onBiometricPromptUnanswered(
            lockedPackage = lockedPackage,
            triggeringPackage = triggeringPackageName ?: ""
        )
    }

    /**
     * Ends the lock session of a prompt that left the screen without an answer: another app came
     * in front of it, Home or Recents was opened, or the screen went off.
     *
     * Android cancels the prompt then, but the biometric library only passes errors on while this
     * activity is started, so [fallBackToLockScreen] never hears of it. Left alone, the service
     * would keep believing an authentication is in flight, and then that a lock screen is showing,
     * and skip every locked app until the screen went off.
     *
     * If this prompt had already taken the lock screen down, the lock screen flag is cleared and
     * the service is told, so it can lock whatever app is now in front. If the lock screen never
     * came down, it is still up and still owns the flag, so both are left as they are.
     */
    private fun releaseUnansweredPrompt() {
        if (sessionEnded || isAuthenticated) return
        sessionEnded = true

        LogUtils.d(TAG, "Biometric prompt for $lockedPackageName went away unanswered")
        AppLockManager.reportBiometricAuthFinished()

        try {
            biometricPrompt?.cancelAuthentication()
        } catch (e: Exception) {
            Log.w(TAG, "Could not cancel the biometric prompt", e)
        }

        if (!hasHiddenLockScreen) return
        AppLockManager.isLockScreenShown.set(false)
        val lockedPackage = lockedPackageName ?: return
        AppLockManager.lockScreenHost?.onBiometricPromptUnanswered(
            lockedPackage = lockedPackage,
            triggeringPackage = triggeringPackageName ?: ""
        )
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        hideLockScreen()
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        if (isFinishing) {
            AppLockManager.reportBiometricAuthFinished()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        if (isWatchingReturnedLockScreen) takeDownReturnedLockScreen()
        releaseUnansweredPrompt()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        stopWatchingReturnedLockScreen()
        // A backstop for a destroy that did not pass through onStop's release.
        if (!isChangingConfigurations) releaseUnansweredPrompt()
        super.onDestroy()
        // Never leave the backends believing an authentication is still in flight.
        if (!isAuthenticated) {
            AppLockManager.reportBiometricAuthFinished()
        }
    }

    private companion object {
        // Cancels that can come from leaving rather than from the user, like Home pressed on the prompt.
        val CANCEL_ERRORS = setOf(BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED)

        const val RETURNED_LOCK_SCREEN_WATCH_MS = 3_000L
        const val WATCH_TICK_MS = 100L
    }
}
