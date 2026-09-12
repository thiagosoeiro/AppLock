package dev.pranav.applock.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.getSystemService
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.PhoneLocker
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.core.utils.canAuthenticateBiometrics
import dev.pranav.applock.core.utils.enableAccessibilityServiceWithShizuku
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.features.lockscreen.ui.LockScreenOverlayManager
import dev.pranav.applock.features.lockscreen.ui.startBiometricPrompt
import dev.pranav.applock.services.AppLockConstants.ACCESSIBILITY_SETTINGS_CLASSES
import dev.pranav.applock.services.AppLockConstants.EXCLUDED_APPS
import java.lang.ref.WeakReference
import rikka.shizuku.Shizuku

@SuppressLint("AccessibilityPolicy")
class AppLockAccessibilityService : AccessibilityService() {
    private val appLockRepository: AppLockRepository by lazy { applicationContext.appLockRepository() }
    private val keyboardPackages: List<String> by lazy { getKeyboardPackageNames() }

    // Our label as Settings displays it; the anti-uninstall checks match on it, so they follow any rename.
    // Looked up on every check rather than cached: the label is translated, and a cached copy keeps
    // the old language's name after the phone's language changes, so every check would miss.
    // PackageManager caches the string itself and drops that cache on a configuration change.
    private val ownLabel: String
        get() = applicationInfo.loadLabel(packageManager).toString()

    // Our version as Settings prints it on the App info page. Lists of apps never show a version,
    // which is what separates that page from a list our name merely appears in.
    private val ownVersionName: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            logError("Could not read own version name", e)
            ""
        }
    }

    private var lastForegroundPackage = ""

    private var overlayManager: LockScreenOverlayManager? = null
    private lateinit var mainHandler: Handler

    enum class BiometricState {
        IDLE, AUTH_STARTED
    }

    companion object {
        private const val TAG = "AppLockAccessibility"
        private const val DEVICE_ADMIN_SETTINGS_PACKAGE = "com.android.settings"
        private const val APP_PACKAGE_PREFIX = "dev.pranav.applock"

        @Volatile
        var isServiceRunning = false

        // The service while Android has it connected. Its lock action only works through that
        // connection, which Android drops before it unbinds, so this is cleared on unbind.
        @Volatile
        private var connected: WeakReference<AppLockAccessibilityService>? = null

        /** The connected service, for [PhoneLocker]; null once Android has let go of it. */
        val connectedInstance: AppLockAccessibilityService?
            get() = connected?.get()
    }

    private val screenStateReceiver = object: android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    LogUtils.d(TAG, "Screen off detected. Resetting AppLock state.")
                    AppLockManager.isLockScreenShown.set(false)
                    AppLockManager.clearAllUnlockStates()
                }
            } catch (e: Exception) {
                logError("Error in screenStateReceiver", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            isServiceRunning = true
            AppLockManager.currentBiometricState = BiometricState.IDLE
            AppLockManager.isLockScreenShown.set(false)
            startPrimaryBackendService()

            mainHandler = Handler(mainLooper)

            overlayManager = LockScreenOverlayManager(this)
            AppLockManager.lockScreenHost = lockScreenHost

            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
        } catch (e: Exception) {
            logError("Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                packageNames = null
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }

            connected = WeakReference(this)
            Log.d(TAG, "Accessibility service connected")
            appLockRepository.setActiveBackend(BackendImplementation.ACCESSIBILITY)
        } catch (e: Exception) {
            logError("Error in onServiceConnected", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            logError("Unhandled error in onAccessibilityEvent", e)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        if (appLockRepository.isAntiUninstallEnabled() &&
            event.packageName == DEVICE_ADMIN_SETTINGS_PACKAGE
        ) {
            checkForDeviceAdminDeactivation(event)
        }

        if (!appLockRepository.isProtectionActive()) {
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            Log.d(TAG, "Windows changed event received. Checking recents state.")
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.text == packageManager.getApplicationInfo(
                    event.packageName.toString(),
                    0
                ).loadLabel(packageManager)
            ) {
                Log.d(TAG, "Ignoring recents bug event: ${event.text}")
                return
            }
            if (isRecentlyOpened(event)) {
                Log.d(TAG, "Recents opened")
            }
            handleWindowStateChanged(event)
        } else {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Skip if device is locked or app is excluded
        if (!isValidPackageForLocking(packageName)) {
            return
        }

        try {
            processPackageLocking(packageName)
        } catch (e: Exception) {
            logError("Error processing package locking for $packageName", e)
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val isHomeScreen = isHomeScreen(event)

        when {
            isHomeScreenTransition(event) -> {
                LogUtils.d(TAG, "Transitioning to home screen from recents")
                releaseUnlockForNeutralSurface(event.packageName?.toString())
            }

            isHomeScreen -> {
                LogUtils.d(TAG, "On home screen")
                releaseUnlockForNeutralSurface(event.packageName?.toString())
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun isRecentlyOpened(event: AccessibilityEvent): Boolean {
        return (event.packageName == getSystemDefaultLauncherPackageName() &&
                event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) && event.className == "" ||
                (event.text.toString().lowercase().contains("recent apps"))
    }

    private fun isHomeScreen(event: AccessibilityEvent): Boolean {
        return event.packageName == getSystemDefaultLauncherPackageName() &&
                event.className == "com.android.launcher3.uioverrides.QuickstepLauncher" &&
                event.text.toString().lowercase().contains("home screen")
    }

    @SuppressLint("InlinedApi")
    private fun isHomeScreenTransition(event: AccessibilityEvent): Boolean {
        return event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED &&
                event.packageName == getSystemDefaultLauncherPackageName()
    }

    /**
     * Called when the launcher - home screen or recents - takes the foreground. The user has not
     * necessarily moved on to anything else, so the unlock is held for a short return window
     * rather than dropped outright.
     */
    private fun releaseUnlockForNeutralSurface(newPackage: String?) {
        val unlockedApp = AppLockManager.temporarilyUnlockedApp
        if (unlockedApp.isEmpty() || newPackage == unlockedApp) return

        if (newPackage != null && newPackage in appLockRepository.getTriggerExcludedApps()) {
            LogUtils.d(TAG, "$newPackage is trigger excluded, keeping $unlockedApp unlocked")
            return
        }

        LogUtils.d(TAG, "Holding unlock for $unlockedApp while on $newPackage")
        AppLockManager.holdUnlockForReturn(unlockedApp)
        AppLockManager.clearTemporarilyUnlockedApp()
    }

    /**
     * The launcher draws both the home screen and, on most devices, the recents switcher. Neither
     * means the user has opened something else, so an unlock survives them for a short window.
     * Everything else that is not a real app - system UI, the intent resolver, keyboards - is
     * already filtered out by [isValidPackageForLocking] before this is reached.
     */
    private fun isNeutralSurface(packageName: String): Boolean {
        val launcher = getSystemDefaultLauncherPackageName()
        return launcher.isNotEmpty() && packageName == launcher
    }

    private fun isValidPackageForLocking(packageName: String): Boolean {
        // Check if device is locked
        if (applicationContext.isDeviceLocked()) {
            AppLockManager.clearAllUnlockStates()
            return false
        }

        // Check if accessibility should handle locking
        if (!shouldAccessibilityHandleLocking()) {
            return false
        }

        // Skip excluded packages
        if (packageName == APP_PACKAGE_PREFIX ||
            packageName in keyboardPackages ||
            packageName in EXCLUDED_APPS
        ) {
            return false
        }

        // Skip known recents classes
        return true
    }

    private fun processPackageLocking(packageName: String) {
        val currentForegroundPackage = packageName
        val triggeringPackage = lastForegroundPackage
        lastForegroundPackage = currentForegroundPackage

        // Skip if triggering package is excluded
        if (triggeringPackage in appLockRepository.getTriggerExcludedApps()) {
            return
        }

        val isNeutral = isNeutralSurface(currentForegroundPackage)

        // Fix for "Lock Immediately" not working when switching between apps
        val unlockedApp = AppLockManager.temporarilyUnlockedApp
        if (unlockedApp.isNotEmpty() &&
            unlockedApp != currentForegroundPackage &&
            currentForegroundPackage !in appLockRepository.getTriggerExcludedApps()
        ) {
            LogUtils.d(
                TAG,
                "Switched from unlocked app $unlockedApp to $currentForegroundPackage."
            )
            if (isNeutral) {
                AppLockManager.holdUnlockForReturn(unlockedApp)
            }
            AppLockManager.clearTemporarilyUnlockedApp()
        }

        // A real app taking over ends any pending return - the user went somewhere else rather
        // than coming back. The held app itself is exempt: that is exactly the return we allow.
        if (!isNeutral && !AppLockManager.isPendingReturn(currentForegroundPackage)) {
            AppLockManager.dropPendingReturn()
        }

        checkAndLockApp(currentForegroundPackage, triggeringPackage, System.currentTimeMillis())
    }

    private fun shouldAccessibilityHandleLocking(): Boolean {
        return appLockRepository.getBackendImplementation() == BackendImplementation.ACCESSIBILITY
    }

    private fun checkAndLockApp(packageName: String, triggeringPackage: String, currentTime: Long) {
        // Return early if lock screen is already shown or biometric auth is in progress
        if (AppLockManager.currentBiometricState == BiometricState.AUTH_STARTED) {
            return
        }

        // Return if package is not locked
        if (packageName !in appLockRepository.getLockedApps()) {
            return
        }

        val unlockDurationMinutes = appLockRepository.getUnlockTimeDuration()

        LogUtils.d(
            TAG,
            "checkAndLockApp: pkg=$packageName, duration=$unlockDurationMinutes min, currentTime=$currentTime, isLockScreenShown=${AppLockManager.isLockScreenShown.get()}"
        )

        if (!AppLockManager.shouldShowLockScreen(packageName, currentTime, unlockDurationMinutes)) {
            return
        }

        if (AppLockManager.isLockScreenShown.get() ||
            AppLockManager.currentBiometricState == BiometricState.AUTH_STARTED
        ) {
            LogUtils.d(TAG, "Lock screen already shown or biometric auth in progress, skipping")
            return
        }

        showLockScreenOverlay(packageName, triggeringPackage)
    }

    /**
     * Lets the biometric prompt put this service's overlay back up once it is done with it.
     */
    private val lockScreenHost = object: AppLockManager.LockScreenHost {
        override fun showLockScreen(
            packageName: String,
            triggeringPackage: String,
            autoPromptBiometrics: Boolean
        ) {
            showLockScreenOverlay(packageName, triggeringPackage, autoPromptBiometrics)
        }

        override fun hideLockScreen() {
            mainHandler.post { overlayManager?.removeOverlay() }
        }
    }

    private fun showLockScreenOverlay(
        packageName: String,
        triggeringPackage: String,
        autoPromptBiometrics: Boolean = true
    ) {
        // A lock screen returning from a cancelled prompt has to get through while the flag is
        // still set - it is the same lock session, not a second one.
        if (autoPromptBiometrics && AppLockManager.isLockScreenShown.get()) return

        LogUtils.d(TAG, "Showing overlay for: $packageName")

        mainHandler.post {
            AppLockManager.isLockScreenShown.set(true)
            overlayManager?.showOverlay(
                lockedPackageName = packageName,
                triggeringPackageName = triggeringPackage,
                onUnlock = {
                    AppLockManager.isLockScreenShown.set(false)
                    AppLockManager.reportBiometricAuthFinished()
                    AppLockManager.unlockApp(packageName)
                },
                onExit = {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    Thread.sleep(200)
                    AppLockManager.isLockScreenShown.set(false)
                    AppLockManager.reportBiometricAuthFinished()
                }
            )

            // The overlay is up first and stays up until the prompt is on screen, so the locked
            // app is never briefly visible behind it.
            if (autoPromptBiometrics && canPromptBiometrics()) {
                LogUtils.d(TAG, "Auto-prompting biometrics for: $packageName")
                startBiometricPrompt(packageName, triggeringPackage)
            }
        }
    }

    private fun canPromptBiometrics(): Boolean =
        appLockRepository.isBiometricAuthEnabled() && canAuthenticateBiometrics()

    //private fun showLockScreenOverlay(packageName: String, triggeringPackage: String) {
    //    LogUtils.d(TAG, "Locked app detected: $packageName. Showing overlay.")
    //    AppLockManager.isLockScreenShown.set(true)
    //
    //    val intent = Intent(this, PasswordOverlayActivity::class.java).apply {
    //        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
    //                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
    //                Intent.FLAG_ACTIVITY_NO_ANIMATION or
    //                Intent.FLAG_FROM_BACKGROUND or
    //                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    //        putExtra("locked_package", packageName)
    //        putExtra("triggering_package", triggeringPackage)
    //    }
    //
    //    try {
    //        startActivity(intent)
    //    } catch (e: Exception) {
    //        logError("Failed to start password overlay", e)
    //        AppLockManager.isLockScreenShown.set(false)
    //    }
    //}

    private fun checkForDeviceAdminDeactivation(event: AccessibilityEvent) {
        Log.d(TAG, "Checking for device admin deactivation for event: $event")

        // Check if user is trying to deactivate the accessibility service
        if (isDeactivationAttempt(event)) {
            Log.d(TAG, "Blocking accessibility service deactivation")
            blockDeactivationAttempt("accessibility settings page")
            return
        }

        // Check if user reached our own App info page (Uninstall / Force stop / Clear data live here)
        if (isOwnAppInfoPage(event)) {
            Log.d(TAG, "Blocking own app info page")
            blockDeactivationAttempt("app info page")
            return
        }

        // Check if on the confirmation page for our own device admin
        val isOwnDeviceAdminPage = isOwnDeviceAdminPage(event)

        LogUtils.d(TAG, "User is on our device admin page: $isOwnDeviceAdminPage, $event")

        if (!isOwnDeviceAdminPage) {
            return
        }

        blockDeviceAdminDeactivation()
    }

    private fun isDeactivationAttempt(event: AccessibilityEvent): Boolean {
        val label = ownLabel
        val isAccessibilitySettings = event.className in ACCESSIBILITY_SETTINGS_CLASSES &&
                event.text.any { it.contains(label) }
        val isSubSettings = event.className == "com.android.settings.SubSettings" &&
                event.text.any { it.contains(label) }
        val isAlertDialog =
            event.packageName == "com.google.android.packageinstaller" && event.className == "android.app.AlertDialog" && event.text.toString()
                .contains(label)

        return isAccessibilitySettings || isSubSettings || isAlertDialog
    }

    /**
     * True when the foreground window is our own App info page in Settings, where Uninstall, Force
     * stop and Clear data live.
     *
     * The page title arrives in [AccessibilityEvent.getText] as a generic string ("App info" on
     * One UI), never the app name, so the old name-in-event check could not catch it on most OEMs.
     * The name is in the window content instead, as the header under the icon, so we read that.
     *
     * Matching the name alone is not enough. The apps list and Accessibility > Installed apps are
     * windows that carry our name too, as one row among many, and returning to either restores the
     * scroll position that shows it - which locked the phone while merely browsing Settings. So
     * require our version number on screen as well: Settings prints it on the App info page, app
     * lists never do, and a version needs no localised string to recognise.
     *
     * Fails open. If an OEM's page omits the version this does not fire, leaving that page
     * unguarded rather than locking the phone on the wrong screen - and Uninstall and Force stop
     * there are blocked and greyed out by device admin regardless.
     */
    private fun isOwnAppInfoPage(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        if (event.packageName != DEVICE_ADMIN_SETTINGS_PACKAGE) return false
        if (ownVersionName.isEmpty()) return false

        val root = rootInActiveWindow ?: return false
        return findNodeWithTextContaining(root, ownLabel) != null &&
                findNodeWithTextContaining(root, ownVersionName) != null
    }

    @SuppressLint("InlinedApi")
    private fun blockDeactivationAttempt(reason: String) {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            PhoneLocker.lockPhone(this, reason)
        } catch (e: Exception) {
            logError("Error blocking deactivation attempt", e)
        }
    }

    /**
     * True when the foreground window is the confirmation page for *our* device admin - the page
     * carrying the button that deactivates it.
     *
     * Narrower than it was. It used to match on the class name alone, the device admin list
     * included, so opening that section or any other app's admin page bounced the user out.
     * Deactivating ours still has to pass through this page, and [blockDeviceAdminDeactivation]
     * only acts while our admin is already active, so granting it in the first place still works.
     *
     * The old content-description branch is gone with it: it lowercased the text and then looked
     * for "Device admin app", so it could never match.
     */
    private fun isOwnDeviceAdminPage(event: AccessibilityEvent): Boolean {
        if (event.className?.contains("DeviceAdminAdd") != true) return false

        val root = rootInActiveWindow ?: return false
        return findNodeWithTextContaining(root, ownLabel) != null
    }

    @SuppressLint("InlinedApi")
    private fun blockDeviceAdminDeactivation() {
        try {
            val dpm: DevicePolicyManager? = getSystemService()
            val component = ComponentName(this, DeviceAdmin::class.java)

            if (dpm?.isAdminActive(component) == true) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_HOME)
                Thread.sleep(100)
                PhoneLocker.lockPhone(this, "device admin page")
                Toast.makeText(
                    this,
                    "This action isn't allowed.",
                    Toast.LENGTH_LONG
                ).show()
                Log.w(TAG, "Blocked device admin deactivation attempt.")
            }
        } catch (e: Exception) {
            logError("Error blocking device admin deactivation", e)
        }
    }

    private fun findNodeWithTextContaining(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        return try {
            if (node.text?.toString()?.contains(text, ignoreCase = true) == true) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findNodeWithTextContaining(child, text)
                if (result != null) return result
            }
            null
        } catch (e: Exception) {
            logError("Error finding node with text: $text", e)
            null
        }
    }

    private fun getKeyboardPackageNames(): List<String> {
        return try {
            getSystemService<InputMethodManager>()?.enabledInputMethodList?.map { it.packageName }
                ?: emptyList()
        } catch (e: Exception) {
            logError("Error getting keyboard package names", e)
            emptyList()
        }
    }

    fun getSystemDefaultLauncherPackageName(): String =
        applicationContext.defaultLauncherPackageName()

    private fun startPrimaryBackendService() {
        try {
            AppLockManager.stopAllOtherServices(this, AppLockAccessibilityService::class.java)

            when (appLockRepository.getBackendImplementation()) {
                BackendImplementation.SHIZUKU -> {
                    Log.d(TAG, "Starting Shizuku service as primary backend")
                    startService(Intent(this, ShizukuAppLockService::class.java))
                }

                BackendImplementation.USAGE_STATS -> {
                    Log.d(TAG, "Starting Experimental service as primary backend")
                    startService(Intent(this, UsageLockService::class.java))
                }

                else -> {
                    Log.d(TAG, "Accessibility service is the primary backend.")
                }
            }
        } catch (e: Exception) {
            logError("Error starting primary backend service", e)
        }
    }

    override fun onInterrupt() {
        try {
            LogUtils.d(TAG, "Accessibility service interrupted")
        } catch (e: Exception) {
            logError("Error in onInterrupt", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return try {
            Log.d(TAG, "Accessibility service unbound")
            isServiceRunning = false
            connected = null

            if (Shizuku.pingBinder() && appLockRepository.isAntiUninstallEnabled()) {
                enableAccessibilityServiceWithShizuku(ComponentName(packageName, javaClass.name))
            }

            super.onUnbind(intent)
        } catch (e: Exception) {
            logError("Error in onUnbind", e)
            super.onUnbind(intent)
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()
            isServiceRunning = false
            connected = null
            LogUtils.d(TAG, "Accessibility service destroyed")

            AppLockManager.lockScreenHost = null
            overlayManager?.removeOverlay()

            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: IllegalArgumentException) {
                // Ignore if not registered
                Log.w(TAG, "Receiver not registered or already unregistered")
            }

            AppLockManager.isLockScreenShown.set(false)
        } catch (e: Exception) {
            logError("Error in onDestroy", e)
        }
    }

    /**
     * Logs errors silently without crashing the service.
     * Only logs to debug level to avoid unnecessary noise in production.
     */
    private fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }
}
