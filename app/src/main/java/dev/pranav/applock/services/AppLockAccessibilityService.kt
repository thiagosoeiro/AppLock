package dev.pranav.applock.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
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

    // Anti-uninstall's view of the last page Settings or the package installer opened: which one it
    // is, until when its content is still being checked, and when the installer last opened an
    // uninstall screen, which says whether its dialog is about uninstalling.
    private var guardedPagePackage = ""
    private var guardedPageClass = ""
    private var guardedPageCheckUntil = 0L
    private var installerUninstallScreenAt = 0L
    private var guardedPageContentCheckPosted = false
    private val guardedPageRecheck = Runnable { checkGuardedPageContent() }
    private val guardedPageContentCheck = Runnable {
        guardedPageContentCheckPosted = false
        checkGuardedPageContent()
    }

    private var overlayManager: LockScreenOverlayManager? = null
    private lateinit var mainHandler: Handler

    enum class BiometricState {
        IDLE, AUTH_STARTED
    }

    companion object {
        private const val TAG = "AppLockAccessibility"
        private const val DEVICE_ADMIN_SETTINGS_PACKAGE = "com.android.settings"
        private const val APP_PACKAGE_PREFIX = "dev.pranav.applock"

        // Part of every package installer's package name, which differs between phones.
        private const val PACKAGE_INSTALLER_MARKER = "packageinstaller"

        // After a Settings page opens, how long anti-uninstall keeps checking its content, and how
        // soon after a content change it checks again.
        private const val GUARDED_PAGE_CHECK_WINDOW_MS = 1_000L
        private const val GUARDED_PAGE_CONTENT_CHECK_DELAY_MS = 50L

        // How soon after the package installer opens an uninstall screen its dialog must appear.
        private const val INSTALLER_UNINSTALL_WINDOW_MS = 3_000L

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
                // No delay: Android holds events this long and keeps only the newest of each type,
                // which could drop the event for a guarded page opening.
                notificationTimeout = 0L
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
        if (appLockRepository.isAntiUninstallEnabled() && isGuardedPackage(event.packageName)) {
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

    // Settings holds our App info, Accessibility and device admin pages; the package installer holds
    // the uninstall dialog.
    private fun isGuardedPackage(packageName: CharSequence?): Boolean =
        packageName == DEVICE_ADMIN_SETTINGS_PACKAGE ||
                packageName?.contains(PACKAGE_INSTALLER_MARKER) == true

    private fun checkForDeviceAdminDeactivation(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> checkGuardedPageOpened(event, packageName)

            // More of the open page has been drawn: check it again shortly, once per burst.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (packageName != guardedPagePackage ||
                    SystemClock.uptimeMillis() > guardedPageCheckUntil ||
                    guardedPageContentCheckPosted
                ) return
                guardedPageContentCheckPosted = true
                mainHandler.postDelayed(guardedPageContentCheck, GUARDED_PAGE_CONTENT_CHECK_DELAY_MS)
            }
        }
    }

    /**
     * A page or dialog opened in Settings or the package installer. Its event carries the class and
     * title, which is enough for the Accessibility page and the uninstall dialog. App info and the
     * device admin page are told apart by their content, which may be drawn after this event, so
     * that is checked now, twice more shortly after, and on content changes, for up to a second.
     */
    private fun checkGuardedPageOpened(event: AccessibilityEvent, packageName: String) {
        stopGuardedPageChecks()
        val className = event.className?.toString().orEmpty()
        guardedPagePackage = packageName
        guardedPageClass = className
        if (className.contains(PACKAGE_INSTALLER_MARKER)) {
            installerUninstallScreenAt =
                if (className.contains("Uninstall", ignoreCase = true)) SystemClock.uptimeMillis() else 0L
        }
        LogUtils.d(TAG, "Anti-uninstall sees $packageName / $className")

        if (isAccessibilityServicePage(event)) {
            Log.d(TAG, "Blocking accessibility service deactivation")
            blockDeactivationAttempt("accessibility settings page")
            return
        }

        if (isOwnUninstallDialog(event)) {
            Log.d(TAG, "Blocking the uninstall dialog")
            blockDeactivationAttempt("uninstall dialog")
            return
        }

        if (packageName != DEVICE_ADMIN_SETTINGS_PACKAGE) return

        guardedPageCheckUntil = SystemClock.uptimeMillis() + GUARDED_PAGE_CHECK_WINDOW_MS
        mainHandler.postDelayed(guardedPageRecheck, 150)
        mainHandler.postDelayed(guardedPageRecheck, 400)
        checkGuardedPageContent()
    }

    /** Looks for our device admin page or our App info page in the open Settings window. */
    private fun checkGuardedPageContent() {
        if (SystemClock.uptimeMillis() > guardedPageCheckUntil) return

        // Until Settings' window is the active one its content can't be read; a later check will.
        val root = rootInActiveWindow ?: return
        if (root.packageName != DEVICE_ADMIN_SETTINGS_PACKAGE) return

        if (guardedPageClass.contains("DeviceAdminAdd")) {
            if (!isOwnDeviceAdminPage(root)) return
            stopGuardedPageChecks()

            // The app opens this same page itself to grant the admin or force-lock; let that through.
            if (DeviceAdmin.isOwnGrantPending(this)) {
                LogUtils.d(TAG, "Not blocking our device admin page: the app opened it to grant admin")
                return
            }
            blockDeviceAdminDeactivation()
            return
        }

        if (isOwnAppInfoPage(root)) {
            Log.d(TAG, "Blocking own app info page")
            blockDeactivationAttempt("app info page")
        }
    }

    private fun stopGuardedPageChecks() {
        guardedPageCheckUntil = 0L
        guardedPageContentCheckPosted = false
        mainHandler.removeCallbacks(guardedPageRecheck)
        mainHandler.removeCallbacks(guardedPageContentCheck)
    }

    // Our service's page in Accessibility settings, which Settings titles with our name.
    private fun isAccessibilityServicePage(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString() ?: return false
        if (className !in ACCESSIBILITY_SETTINGS_CLASSES &&
            className != "com.android.settings.SubSettings"
        ) {
            return false
        }
        val label = ownLabel
        return event.text.any { it.contains(label) }
    }

    /**
     * The package installer asking to uninstall us. The installer shows our name in the same kind of
     * dialog when installing an update to this app, which must not lock the phone, so this also needs
     * the installer to have just opened an uninstall screen (UninstallerActivity or UninstallLaunch
     * on stock Android). Fails open: if a phone's installer names its screens differently this
     * doesn't fire, and the log shows the names it used.
     */
    private fun isOwnUninstallDialog(event: AccessibilityEvent): Boolean {
        if (event.packageName?.contains(PACKAGE_INSTALLER_MARKER) != true) return false
        if (installerUninstallScreenAt == 0L ||
            SystemClock.uptimeMillis() - installerUninstallScreenAt > INSTALLER_UNINSTALL_WINDOW_MS
        ) {
            return false
        }
        val label = ownLabel
        return event.text.any { it.contains(label) }
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
    private fun isOwnAppInfoPage(root: AccessibilityNodeInfo): Boolean {
        if (ownVersionName.isEmpty()) return false

        val wanted = listOf(ownLabel, ownVersionName)
        return findTexts(root, wanted).size == wanted.size
    }

    /**
     * Leaves the page and locks the phone. Back comes first, so the page is gone before the lock and
     * unlocking doesn't land on it and lock again. Home waits until after the lock, since stopping
     * the attempt doesn't depend on it.
     */
    @SuppressLint("InlinedApi")
    private fun blockDeactivationAttempt(reason: String) {
        stopGuardedPageChecks()
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            PhoneLocker.lockPhone(this, reason)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            logError("Error blocking deactivation attempt", e)
        }
    }

    /**
     * True when [root], an open DeviceAdminAdd page (the caller checks the class), is the
     * confirmation page for *our* device admin - the page carrying the button that deactivates it.
     *
     * Narrower than it was. It used to match on the class name alone, the device admin list
     * included, so opening that section or any other app's admin page bounced the user out.
     * Deactivating ours still has to pass through this page, and [blockDeviceAdminDeactivation]
     * only acts while our admin is already active, so granting it in the first place still works.
     *
     * The old content-description branch is gone with it: it lowercased the text and then looked
     * for "Device admin app", so it could never match.
     */
    private fun isOwnDeviceAdminPage(root: AccessibilityNodeInfo): Boolean =
        findTexts(root, listOf(ownLabel)).isNotEmpty()

    @SuppressLint("InlinedApi")
    private fun blockDeviceAdminDeactivation() {
        try {
            val dpm: DevicePolicyManager? = getSystemService()
            val component = ComponentName(this, DeviceAdmin::class.java)

            if (dpm?.isAdminActive(component) == true) {
                // Same order as blockDeactivationAttempt, without the old 100 ms pause before locking.
                performGlobalAction(GLOBAL_ACTION_BACK)
                PhoneLocker.lockPhone(this, "device admin page")
                performGlobalAction(GLOBAL_ACTION_HOME)
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

    /**
     * Which of [wanted] appear in the text of [root] or any node under it, ignoring case. One walk
     * covers all of them and stops once every one has been seen, since reading nodes can mean calls
     * into the other app's process.
     */
    private fun findTexts(root: AccessibilityNodeInfo, wanted: List<String>): Set<String> {
        val found = mutableSetOf<String>()

        fun visit(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()
            if (text != null) {
                wanted.filterTo(found) { text.contains(it, ignoreCase = true) }
            }
            for (i in 0 until node.childCount) {
                if (found.size == wanted.size) return
                visit(node.getChild(i) ?: continue)
            }
        }

        try {
            visit(root)
        } catch (e: Exception) {
            logError("Error reading window content", e)
        }
        return found
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

            // Turning anti-uninstall off with the PIN clears the flag first, so the service going
            // away with the flag still on means someone switched it off in Settings. Android has
            // already dropped the connection, so this lock comes from device admin.
            if (appLockRepository.isAntiUninstallEnabled()) {
                PhoneLocker.lockPhone(this, "accessibility service turned off")
            }

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
            if (::mainHandler.isInitialized) stopGuardedPageChecks()

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
