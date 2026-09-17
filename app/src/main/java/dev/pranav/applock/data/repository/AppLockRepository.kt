package dev.pranav.applock.data.repository

import android.content.Context
import dev.pranav.applock.core.network.TrustedNetworkMonitor
import dev.pranav.applock.data.manager.BackendServiceManager
import dev.pranav.applock.services.AppLockManager

/**
 * Main repository that coordinates between different specialized repositories and managers.
 * Provides a unified interface for all app lock functionality.
 */
class AppLockRepository(private val context: Context) {

    private val preferencesRepository = PreferencesRepository(context)
    private val lockedAppsRepository = LockedAppsRepository(context)
    private val backendServiceManager = BackendServiceManager()

    fun getLockedApps(): Set<String> = lockedAppsRepository.getLockedApps()
    fun addLockedApp(packageName: String) {
        lockedAppsRepository.addLockedApp(packageName)
        AppLockManager.clearAppUnlockState(packageName)
    }

    fun addMultipleLockedApps(packageNames: Set<String>) {
        lockedAppsRepository.addMultipleLockedApps(packageNames)
        packageNames.forEach(AppLockManager::clearAppUnlockState)
    }
    fun removeLockedApp(packageName: String) = lockedAppsRepository.removeLockedApp(packageName)
    fun isAppLocked(packageName: String): Boolean = lockedAppsRepository.isAppLocked(packageName)

    fun getLockedAppNames(): Map<String, String> = lockedAppsRepository.getLockedAppNames()
    fun saveLockedAppNames(names: Map<String, String>) =
        lockedAppsRepository.saveLockedAppNames(names)

    fun getTriggerExcludedApps(): Set<String> = lockedAppsRepository.getTriggerExcludedApps()
    fun addTriggerExcludedApp(packageName: String) =
        lockedAppsRepository.addTriggerExcludedApp(packageName)

    fun removeTriggerExcludedApp(packageName: String) =
        lockedAppsRepository.removeTriggerExcludedApp(packageName)

    fun isAppTriggerExcluded(packageName: String): Boolean =
        lockedAppsRepository.isAppTriggerExcluded(packageName)

    fun getAntiUninstallApps(): Set<String> = lockedAppsRepository.getAntiUninstallApps()
    fun addAntiUninstallApp(packageName: String) =
        lockedAppsRepository.addAntiUninstallApp(packageName)

    fun removeAntiUninstallApp(packageName: String) =
        lockedAppsRepository.removeAntiUninstallApp(packageName)

    fun isAppAntiUninstall(packageName: String): Boolean =
        lockedAppsRepository.isAppAntiUninstall(packageName)

    fun getPassword(): String? = preferencesRepository.getPassword()
    fun setPassword(password: String) = preferencesRepository.setPassword(password)
    fun validatePassword(inputPassword: String): Boolean =
        preferencesRepository.validatePassword(inputPassword)

    fun getPattern(): String? = preferencesRepository.getPattern()
    fun setPattern(pattern: String) = preferencesRepository.setPattern(pattern)
    fun validatePattern(inputPattern: String): Boolean =
        preferencesRepository.validatePattern(inputPattern)

    fun upgradeStoredCredentials() = preferencesRepository.upgradeStoredCredentials()

    fun getPinLength(): Int = preferencesRepository.getPinLength()
    fun getLockoutRemainingMillis(): Long = preferencesRepository.getLockoutRemainingMillis()
    fun clearFailedAttempts() = preferencesRepository.clearFailedAttempts()

    fun setLockType(lockType: String) = preferencesRepository.setLockType(lockType)
    fun getLockType(): String = preferencesRepository.getLockType()

    fun setBiometricAuthEnabled(enabled: Boolean) =
        preferencesRepository.setBiometricAuthEnabled(enabled)

    fun isBiometricAuthEnabled(): Boolean = preferencesRepository.isBiometricAuthEnabled()

    fun setUseMaxBrightness(enabled: Boolean) = preferencesRepository.setUseMaxBrightness(enabled)
    fun shouldUseMaxBrightness(): Boolean = preferencesRepository.shouldUseMaxBrightness()
    fun setDisableHaptics(enabled: Boolean) = preferencesRepository.setDisableHaptics(enabled)
    fun shouldDisableHaptics(): Boolean = preferencesRepository.shouldDisableHaptics()
    fun setShowSystemApps(enabled: Boolean) = preferencesRepository.setShowSystemApps(enabled)
    fun shouldShowSystemApps(): Boolean = preferencesRepository.shouldShowSystemApps()

    fun setAntiUninstallEnabled(enabled: Boolean) =
        preferencesRepository.setAntiUninstallEnabled(enabled)

    fun isAntiUninstallEnabled(): Boolean = preferencesRepository.isAntiUninstallEnabled()
    fun setProtectEnabled(enabled: Boolean) = preferencesRepository.setProtectEnabled(enabled)
    fun isProtectEnabled(): Boolean = preferencesRepository.isProtectEnabled()

    /**
     * Whether locked apps should be locked right now: the shield is on, and no trusted Wi-Fi network
     * is relaxing it. The lock backends check this; the shield and automation read [isProtectEnabled].
     */
    fun isProtectionActive(): Boolean =
        isProtectEnabled() && !(isTrustedWifiEnabled() && TrustedNetworkMonitor.isOnTrustedNetwork())

    fun setAutomationEnabled(enabled: Boolean) =
        preferencesRepository.setAutomationEnabled(enabled)

    fun isAutomationEnabled(): Boolean = preferencesRepository.isAutomationEnabled()
    fun getAutomationToken(): String? = preferencesRepository.getAutomationToken()
    fun regenerateAutomationToken(): String = preferencesRepository.regenerateAutomationToken()

    fun setTrustedWifiEnabled(enabled: Boolean) =
        preferencesRepository.setTrustedWifiEnabled(enabled)

    fun isTrustedWifiEnabled(): Boolean = preferencesRepository.isTrustedWifiEnabled()
    fun getTrustedWifiSsids(): Set<String> = preferencesRepository.getTrustedWifiSsids()
    fun addTrustedWifiSsid(ssid: String) = preferencesRepository.addTrustedWifiSsid(ssid)
    fun removeTrustedWifiSsid(ssid: String) = preferencesRepository.removeTrustedWifiSsid(ssid)

    fun setScreenTimeoutByNetworkEnabled(enabled: Boolean) =
        preferencesRepository.setScreenTimeoutByNetworkEnabled(enabled)

    fun isScreenTimeoutByNetworkEnabled(): Boolean =
        preferencesRepository.isScreenTimeoutByNetworkEnabled()

    fun setScreenTimeoutTrustedSeconds(seconds: Int) =
        preferencesRepository.setScreenTimeoutTrustedSeconds(seconds)

    fun getScreenTimeoutTrustedSeconds(): Int = preferencesRepository.getScreenTimeoutTrustedSeconds()
    fun setScreenTimeoutAwaySeconds(seconds: Int) =
        preferencesRepository.setScreenTimeoutAwaySeconds(seconds)

    fun getScreenTimeoutAwaySeconds(): Int = preferencesRepository.getScreenTimeoutAwaySeconds()

    fun setLockScreenContentByNetworkEnabled(enabled: Boolean) =
        preferencesRepository.setLockScreenContentByNetworkEnabled(enabled)

    fun isLockScreenContentByNetworkEnabled(): Boolean =
        preferencesRepository.isLockScreenContentByNetworkEnabled()

    /**
     * Whether any option follows the trusted networks, so [TrustedNetworkMonitor] has to run. Only
     * "Open locked apps on trusted Wi-Fi" relaxes locking; see [isProtectionActive].
     */
    fun usesTrustedNetworks(): Boolean =
        isTrustedWifiEnabled() || isScreenTimeoutByNetworkEnabled() ||
                isLockScreenContentByNetworkEnabled()

    fun setIntruderAlertsEnabled(enabled: Boolean) =
        preferencesRepository.setIntruderAlertsEnabled(enabled)

    fun isIntruderAlertsEnabled(): Boolean = preferencesRepository.isIntruderAlertsEnabled()
    fun setIntruderCaptureMode(mode: IntruderCaptureMode) =
        preferencesRepository.setIntruderCaptureMode(mode)

    fun getIntruderCaptureMode(): IntruderCaptureMode = preferencesRepository.getIntruderCaptureMode()
    fun setIntruderThreshold(tries: Int) = preferencesRepository.setIntruderThreshold(tries)
    fun getIntruderThreshold(): Int = preferencesRepository.getIntruderThreshold()
    fun setIntruderLocationEnabled(enabled: Boolean) =
        preferencesRepository.setIntruderLocationEnabled(enabled)

    fun isIntruderLocationEnabled(): Boolean = preferencesRepository.isIntruderLocationEnabled()
    fun setIntruderEmail(apiKey: String?, from: String, to: String) =
        preferencesRepository.setIntruderEmail(apiKey, from, to)

    fun getIntruderApiKey(): String? = preferencesRepository.getIntruderApiKey()
    fun getIntruderEmailFrom(): String = preferencesRepository.getIntruderEmailFrom()
    fun getIntruderEmailTo(): String = preferencesRepository.getIntruderEmailTo()
    fun isIntruderEmailConfigured(): Boolean = preferencesRepository.isIntruderEmailConfigured()
    fun setIntruderSendError(error: String?) = preferencesRepository.setIntruderSendError(error)
    fun getIntruderSendError(): String? = preferencesRepository.getIntruderSendError()

    fun setRemoteLockEnabled(enabled: Boolean) = preferencesRepository.setRemoteLockEnabled(enabled)
    fun isRemoteLockEnabled(): Boolean = preferencesRepository.isRemoteLockEnabled()
    fun setRemoteLockKeyword(keyword: String) = preferencesRepository.setRemoteLockKeyword(keyword)
    fun getRemoteLockKeyword(): String? = preferencesRepository.getRemoteLockKeyword()
    fun setRemoteLockPhoneEnabled(enabled: Boolean) =
        preferencesRepository.setRemoteLockPhoneEnabled(enabled)

    fun isRemoteLockPhoneEnabled(): Boolean = preferencesRepository.isRemoteLockPhoneEnabled()
    fun setRemoteLockEmailEnabled(enabled: Boolean) =
        preferencesRepository.setRemoteLockEmailEnabled(enabled)

    fun isRemoteLockEmailEnabled(): Boolean = preferencesRepository.isRemoteLockEmailEnabled()
    fun setRemoteLockLastMessageAt(sentAt: Long) =
        preferencesRepository.setRemoteLockLastMessageAt(sentAt)

    fun getRemoteLockLastMessageAt(): Long = preferencesRepository.getRemoteLockLastMessageAt()
    fun setRemoteLockLastLockAt(lockedAt: Long) =
        preferencesRepository.setRemoteLockLastLockAt(lockedAt)

    fun getRemoteLockLastLockAt(): Long = preferencesRepository.getRemoteLockLastLockAt()
    fun setRemoteLockLastEmailAt(queuedAt: Long) =
        preferencesRepository.setRemoteLockLastEmailAt(queuedAt)

    fun getRemoteLockLastEmailAt(): Long = preferencesRepository.getRemoteLockLastEmailAt()

    fun setUnlockTimeDuration(minutes: Int) = preferencesRepository.setUnlockTimeDuration(minutes)
    fun getUnlockTimeDuration(): Int = preferencesRepository.getUnlockTimeDuration()
    fun setAutoUnlockEnabled(enabled: Boolean) = preferencesRepository.setAutoUnlockEnabled(enabled)
    fun isAutoUnlockEnabled(): Boolean = preferencesRepository.isAutoUnlockEnabled()

    fun setBackendImplementation(backend: BackendImplementation) =
        preferencesRepository.setBackendImplementation(backend)

    fun getBackendImplementation(): BackendImplementation =
        preferencesRepository.getBackendImplementation()

    fun isShowCommunityLink(): Boolean = preferencesRepository.isShowCommunityLink()
    fun setCommunityLinkShown(shown: Boolean) = preferencesRepository.setCommunityLinkShown(shown)
    fun isShowDonateLink(): Boolean = preferencesRepository.isShowDonateLink(context)
    fun setShowDonateLink(show: Boolean) = preferencesRepository.setShowDonateLink(context, show)

    fun isLoggingEnabled(): Boolean = preferencesRepository.isLoggingEnabled()
    fun setLoggingEnabled(enabled: Boolean) = preferencesRepository.setLoggingEnabled(enabled)

    fun setActiveBackend(backend: BackendImplementation) =
        backendServiceManager.setActiveBackend(backend)

    companion object {
        private const val TAG = "AppLockRepository"

        fun shouldStartService(repository: AppLockRepository, serviceClass: Class<*>): Boolean {
            return repository.backendServiceManager.shouldStartService(
                serviceClass,
                repository.getBackendImplementation()
            )
        }
    }
}

enum class BackendImplementation {
    ACCESSIBILITY,
    USAGE_STATS,
    SHIZUKU
}

/** What an intruder alert records with the front camera. */
enum class IntruderCaptureMode {
    PHOTO,
    VIDEO,

    /** The photo first, then the video, both attached to the one email. */
    BOTH
}
