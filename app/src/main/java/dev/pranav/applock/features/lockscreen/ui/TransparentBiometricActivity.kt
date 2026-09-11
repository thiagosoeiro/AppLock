package dev.pranav.applock.features.lockscreen.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        isAuthenticated = false
        hasHiddenLockScreen = false

        AppLockManager.reportBiometricAuthStarted()

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, authenticationCallback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_verify_title))
            .setNegativeButtonText(getString(R.string.use_pin_button))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
            if (isActivityResumed) hideLockScreen()
        } catch (e: Exception) {
            Log.e(TAG, "Biometric failed to start", e)
            fallBackToLockScreen()
        }
    }

    private val authenticationCallback = object: BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            Log.d(TAG, "Biometric authentication error: $errString ($errorCode)")
            fallBackToLockScreen()
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            isAuthenticated = true
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
     * cancelling would loop straight back into the prompt. If the foreground is already gone (Home
     * pressed, or the system cancelled the prompt), no lock screen is forced over whatever the user
     * moved to; clearing [AppLockManager.isLockScreenShown] instead lets the accessibility service
     * lock the app again the next time it comes forward.
     */
    private fun fallBackToLockScreen() {
        if (isFinishing || isAuthenticated) return

        AppLockManager.reportBiometricAuthFinished()

        val host = AppLockManager.lockScreenHost
        val lockedPackage = lockedPackageName
        if (isActivityResumed && host != null && lockedPackage != null) {
            host.showLockScreen(
                packageName = lockedPackage,
                triggeringPackage = triggeringPackageName ?: "",
                autoPromptBiometrics = false
            )
        } else {
            AppLockManager.isLockScreenShown.set(false)
        }

        finish()
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

    override fun onDestroy() {
        super.onDestroy()
        // Never leave the backends believing an authentication is still in flight.
        if (!isAuthenticated) {
            AppLockManager.reportBiometricAuthFinished()
        }
    }
}
