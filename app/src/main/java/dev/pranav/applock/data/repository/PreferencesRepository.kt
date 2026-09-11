package dev.pranav.applock.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.pranav.applock.core.utils.SecurityUtils

/**
 * Repository for managing application preferences and settings.
 * Handles all SharedPreferences operations with proper separation of concerns.
 */
class PreferencesRepository(context: Context) {

    private val appLockPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_APP_LOCK, Context.MODE_PRIVATE)

    private val settingsPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_SETTINGS, Context.MODE_PRIVATE)

    private val attemptLimiter = UnlockAttemptLimiter(context)

    fun setPassword(password: String) {
        val salt = SecurityUtils.generateSalt()
        val saltedHash = SecurityUtils.hashPassword(password, salt)
        appLockPrefs.edit(commit = true) {
            putString(KEY_PASSWORD, saltedHash)
            putInt(KEY_PIN_LENGTH, SecurityUtils.sanitizePassword(password).length)
        }
    }

    fun getPassword(): String? {
        return appLockPrefs.getString(KEY_PASSWORD, null)
    }

    fun validatePassword(input: String): Boolean = limitAttempts(input) { checkPassword(it) }

    private fun checkPassword(input: String): Boolean {
        val stored = getPassword()
        if (stored.isNullOrBlank()) return false

        val sanitizedInput = SecurityUtils.sanitizePassword(input)

        if (SecurityUtils.isSaltedHash(stored)) {
            val isValid = SecurityUtils.verifyPassword(sanitizedInput, stored)
            // A PIN set before its length was stored gets the length recorded on its next unlock.
            if (isValid && getPinLength() != sanitizedInput.length) {
                appLockPrefs.edit(commit = true) { putInt(KEY_PIN_LENGTH, sanitizedInput.length) }
            }
            return isValid
        }

        if (stored == input || stored == sanitizedInput) {
            setPassword(sanitizedInput)
            return true
        }

        return false
    }

    /**
     * How many characters the PIN or password has, so Auto Unlock can check a PIN only once that
     * many digits are in. 0 while unknown: it is stored when the PIN is set, or on the next unlock
     * for a PIN set before that.
     */
    fun getPinLength(): Int {
        return appLockPrefs.getInt(KEY_PIN_LENGTH, 0)
    }

    fun setPattern(pattern: String) {
        appLockPrefs.edit(commit = true) { putString(KEY_PATTERN, SecurityUtils.hashPassword(pattern)) }
    }

    fun getPattern(): String? {
        return appLockPrefs.getString(KEY_PATTERN, null)
    }

    fun validatePattern(inputPattern: String): Boolean =
        limitAttempts(inputPattern) { checkPattern(it) }

    private fun checkPattern(inputPattern: String): Boolean {
        val storedPattern = getPattern() ?: return false

        if (SecurityUtils.isSaltedHash(storedPattern)) {
            return SecurityUtils.verifyPassword(inputPattern, storedPattern)
        }

        // A pattern set before patterns were hashed, if the upgrade at startup didn't get to it.
        if (SecurityUtils.constantTimeEquals(storedPattern, inputPattern)) {
            setPattern(inputPattern)
            return true
        }

        return false
    }

    /**
     * Brings stored credentials up to the current format when the app starts, before any lock
     * screen checks them. A pattern stored as plain text is hashed; patterns are digits only, so
     * one can never be mistaken for a salted hash.
     */
    fun upgradeStoredCredentials() {
        val storedPattern = getPattern()
        if (!storedPattern.isNullOrBlank() && !SecurityUtils.isSaltedHash(storedPattern)) {
            setPattern(storedPattern)
        }
    }

    /** How long until another PIN, pattern or password may be tried, or 0 if one may be now. */
    fun getLockoutRemainingMillis(): Long = attemptLimiter.remainingMillis()

    /**
     * Forgets wrong entries and ends any wait. Called after a strong biometric, which only the owner
     * can pass, so a thief's wrong tries don't leave the owner one typo away from a long wait.
     */
    fun clearFailedAttempts() = attemptLimiter.recordSuccess()

    /**
     * Runs [check] under the attempt limit. During a wait nothing is checked, so even the right entry
     * is refused. A miss shorter than any PIN, pattern or password that can be set isn't counted: it
     * can never match, so it gives nothing away, and a stray tap on the pattern grid doesn't cost a try.
     */
    private fun limitAttempts(input: String, check: (String) -> Boolean): Boolean {
        if (attemptLimiter.remainingMillis() > 0L) return false

        val isValid = check(input)
        if (isValid) {
            attemptLimiter.recordSuccess()
        } else if (input.length >= MIN_CREDENTIAL_LENGTH) {
            attemptLimiter.recordFailure()
        }
        return isValid
    }

    fun setLockType(lockType: String) {
        settingsPrefs.edit(commit = true) { putString(KEY_LOCK_TYPE, lockType) }
    }

    fun getLockType(): String {
        return settingsPrefs.getString(KEY_LOCK_TYPE, LOCK_TYPE_PIN) ?: LOCK_TYPE_PIN
    }

    fun setBiometricAuthEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_BIOMETRIC_AUTH_ENABLED, enabled) }
    }

    fun isBiometricAuthEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_BIOMETRIC_AUTH_ENABLED, false)
    }

    fun setUseMaxBrightness(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_USE_MAX_BRIGHTNESS, enabled) }
    }

    fun shouldUseMaxBrightness(): Boolean {
        return settingsPrefs.getBoolean(KEY_USE_MAX_BRIGHTNESS, false)
    }

    fun setDisableHaptics(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_DISABLE_HAPTICS, enabled) }
    }

    fun shouldDisableHaptics(): Boolean {
        return settingsPrefs.getBoolean(KEY_DISABLE_HAPTICS, false)
    }

    fun setShowSystemApps(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_SHOW_SYSTEM_APPS, enabled) }
    }

    fun shouldShowSystemApps(): Boolean {
        return settingsPrefs.getBoolean(KEY_SHOW_SYSTEM_APPS, false)
    }

    fun setAntiUninstallEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_ANTI_UNINSTALL, enabled) }
    }

    fun isAntiUninstallEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_ANTI_UNINSTALL, false)
    }

    fun setProtectEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_APPLOCK_ENABLED, enabled) }
    }

    fun isProtectEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_APPLOCK_ENABLED, DEFAULT_PROTECT_ENABLED)
    }

    fun setAutomationEnabled(enabled: Boolean) {
        settingsPrefs.edit(commit = true) { putBoolean(KEY_AUTOMATION_ENABLED, enabled) }
    }

    fun isAutomationEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_AUTOMATION_ENABLED, DEFAULT_AUTOMATION_ENABLED)
    }

    fun getAutomationToken(): String? {
        return appLockPrefs.getString(KEY_AUTOMATION_TOKEN, null)
    }

    /**
     * Replaces the automation token, invalidating any token already handed to an automation app.
     */
    fun regenerateAutomationToken(): String {
        val token = SecurityUtils.generateToken()
        appLockPrefs.edit(commit = true) { putString(KEY_AUTOMATION_TOKEN, token) }
        return token
    }

    fun setUnlockTimeDuration(minutes: Int) {
        settingsPrefs.edit { putInt(KEY_UNLOCK_TIME_DURATION, minutes) }
    }

    fun getUnlockTimeDuration(): Int {
        return settingsPrefs.getInt(KEY_UNLOCK_TIME_DURATION, DEFAULT_UNLOCK_DURATION)
    }

    fun setAutoUnlockEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_AUTO_UNLOCK, enabled) }
    }

    fun isAutoUnlockEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_AUTO_UNLOCK, false)
    }

    fun setBackendImplementation(backend: BackendImplementation) {
        settingsPrefs.edit { putString(KEY_BACKEND_IMPLEMENTATION, backend.name) }
    }

    fun getBackendImplementation(): BackendImplementation {
        val backend = settingsPrefs.getString(
            KEY_BACKEND_IMPLEMENTATION,
            BackendImplementation.ACCESSIBILITY.name
        )
        return try {
            BackendImplementation.valueOf(backend ?: BackendImplementation.ACCESSIBILITY.name)
        } catch (_: IllegalArgumentException) {
            BackendImplementation.ACCESSIBILITY
        }
    }

    fun isShowCommunityLink(): Boolean {
        return !settingsPrefs.getBoolean(KEY_COMMUNITY_LINK_SHOWN, false)
    }

    fun setCommunityLinkShown(shown: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_COMMUNITY_LINK_SHOWN, shown) }
    }

    fun isShowDonateLink(context: Context): Boolean {
        return settingsPrefs.getBoolean(KEY_SHOW_DONATE_LINK, false)
    }

    fun setShowDonateLink(context: Context, show: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_SHOW_DONATE_LINK, show) }
    }

    fun isLoggingEnabled(): Boolean {
        return settingsPrefs.getBoolean(KEY_LOGGING_ENABLED, false)
    }

    fun setLoggingEnabled(enabled: Boolean) {
        settingsPrefs.edit { putBoolean(KEY_LOGGING_ENABLED, enabled) }
    }

    companion object {
        private const val PREFS_NAME_APP_LOCK = "app_lock_prefs"
        private const val PREFS_NAME_SETTINGS = "app_lock_settings"

        private const val KEY_PASSWORD = "password"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_BIOMETRIC_AUTH_ENABLED = "use_biometric_auth"
        private const val KEY_DISABLE_HAPTICS = "disable_haptics"
        private const val KEY_USE_MAX_BRIGHTNESS = "use_max_brightness"
        private const val KEY_ANTI_UNINSTALL = "anti_uninstall"
        private const val KEY_UNLOCK_TIME_DURATION = "unlock_time_duration"
        private const val KEY_BACKEND_IMPLEMENTATION = "backend_implementation"
        private const val KEY_COMMUNITY_LINK_SHOWN = "community_link_shown"
        private const val KEY_SHOW_DONATE_LINK = "show_donate_link"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
        private const val LAST_VERSION_CODE = "last_version_code"
        private const val KEY_APPLOCK_ENABLED = "applock_enabled"
        private const val KEY_AUTO_UNLOCK = "auto_unlock"
        private const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"
        private const val KEY_LOCK_TYPE = "lock_type"
        private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
        private const val KEY_AUTOMATION_TOKEN = "automation_token"
        private const val KEY_PIN_LENGTH = "pin_length"

        private const val DEFAULT_PROTECT_ENABLED = true
        private const val DEFAULT_AUTOMATION_ENABLED = false
        private const val DEFAULT_UNLOCK_DURATION = 0

        /** The shortest PIN, pattern or password the set screens accept. */
        private const val MIN_CREDENTIAL_LENGTH = 4

        const val LOCK_TYPE_PIN = "pin"
        const val LOCK_TYPE_PATTERN = "pattern"
        const val LOCK_TYPE_PASSWORD = "password"
    }
}
