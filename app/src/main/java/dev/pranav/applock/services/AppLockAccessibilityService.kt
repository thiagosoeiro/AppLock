package dev.pranav.applock.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.LocaleList
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.getSystemService
import androidx.core.os.LocaleListCompat
import dev.pranav.applock.R
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
import java.util.Locale
import rikka.shizuku.Shizuku

@SuppressLint("AccessibilityPolicy")
class AppLockAccessibilityService : AccessibilityService() {
    private val appLockRepository: AppLockRepository by lazy { applicationContext.appLockRepository() }
    private val keyboardPackages: List<String> by lazy { getKeyboardPackageNames() }

    // Our label as Settings displays it; the anti-uninstall checks match on it, so they follow any rename.
    // Looked up on every check rather than cached: the label is translated, and a cached copy keeps
    // the old language's name after the phone's language changes, so every check would miss.
    // PackageManager caches the string itself and drops that cache on a configuration change.
    // Settings shows the name in the phone's language, but this service reads it in the app's
    // language, and the two differ once the app is given its own language (Android 13+), so both
    // count. When they agree this holds a single name.
    private val ownLabels: List<String>
        get() {
            val appLanguageLabel = applicationInfo.loadLabel(packageManager).toString()
            val phoneLanguageLabel = phoneLanguageLabel() ?: return listOf(appLanguageLabel)
            return listOf(appLanguageLabel, phoneLanguageLabel).distinct()
        }

    // Our name in the phone's language and the phone locales it was read for; see [phoneLanguageLabel].
    private var phoneLanguageLabelLocales: LocaleListCompat? = null
    private var phoneLanguageLabelCache: String? = null

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

    // The last app whose check was skipped because a biometric prompt was in flight, and the app it
    // was opened from, since the start of the current lock session; see [handleUnansweredPrompt].
    private var skippedDuringPromptPackage = ""
    private var skippedDuringPromptTrigger = ""

    // The re-check waiting to run after an unanswered prompt, if any.
    private var pendingPromptRecheck: Runnable? = null

    // Anti-uninstall's view of the last page Settings or the package installer opened: which one it
    // is, until when its content is still being checked, and when the installer last opened an
    // uninstall screen and last showed our name, which together say it is about uninstalling us.
    private var guardedPagePackage = ""
    private var guardedPageClass = ""
    private var guardedPageCheckUntil = 0L
    private var installerUninstallScreenAt = 0L
    private var installerOurNameAt = 0L

    // Whether the open Settings page has been described in the log; see [describePageShowingOurName].
    private var guardedPageDescribed = false
    private var guardedPageContentCheckPosted = false
    private val guardedPageRecheck = Runnable { checkGuardedPageContent() }
    private val guardedPageContentCheck = Runnable {
        guardedPageContentCheckPosted = false
        checkGuardedPageContent()
    }

    // When a guard last locked the phone; see [isRepeatBlock].
    private var lastBlockAt = 0L

    // When the screen last came on and last went off, for [logScreenOff] and [logScreenOn]. Elapsed
    // real time, so a clock change doesn't move them; 0 until the first of each is seen.
    private var screenOnAt = 0L
    private var screenOffAt = 0L

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

        // How close together the installer's uninstall screen and our name must appear, either order.
        private const val INSTALLER_UNINSTALL_WINDOW_MS = 3_000L

        // View IDs that mark the App info page, from a One UI 8.5 log: the header holds the app name
        // and the page has an Uninstall button. The apps list has neither. Matched as substrings, so
        // the "com.android.settings:id/" prefix is not assumed.
        private const val APP_INFO_HEADER_ID = "entity_header_title"
        private const val APP_INFO_UNINSTALL_ID = "uninstall_button"

        // After a guard locks the phone, how long it ignores the same attempt matching again.
        private const val BLOCK_REPEAT_WINDOW_MS = 2_000L

        // How far short of the screen timeout the screen can go off and still count as the timeout
        // running out: the broadcast arrives a moment after the display is already dark.
        private const val TIMEOUT_MATCH_TOLERANCE_MS = 2_000L

        // A screen coming back within this of going off went off and straight back on, rather than
        // being woken by someone picking the phone up.
        private const val SCREEN_BOUNCE_WINDOW_MS = 2_000L

        // After a prompt goes away unanswered, how long to wait before checking the app in front,
        // so that Home or Recents has reported the launcher by then.
        private const val PROMPT_RECHECK_DELAY_MS = 300L

        // Draws notifications, the notification shade and the status bar.
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

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
                    logScreenOff()
                    AppLockManager.isLockScreenShown.set(false)
                    AppLockManager.clearAllUnlockStates()
                    AppLockManager.clearBiometricPromptInterruptions()
                    cancelPromptRecheck()
                    takeDownLockScreenIfPhoneLocked()
                } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    logScreenOn()
                    // The phone may only have locked after the screen went off.
                    takeDownLockScreenIfPhoneLocked()
                } else if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    // Unlocked: a guard matching from now on is a new attempt, not a repeat.
                    lastBlockAt = 0L
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
                addAction(Intent.ACTION_SCREEN_ON)
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
                // View IDs let the anti-uninstall diagnostics say how Settings built a page.
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
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

        // Only a change of package can be mistaken for switching apps, so only then is the window looked up.
        if (packageName != lastForegroundPackage && isFromSystemUiWindow(event)) {
            LogUtils.d(
                TAG,
                "Ignored $packageName (${event.className}, ${AccessibilityEvent.eventTypeToString(event.eventType)}): drawn by System UI, such as its notification"
            )
            return
        }

        try {
            processPackageLocking(packageName, event)
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
     * Secure Folder's home and lock screen count the same way; see
     * [AppLockConstants.NEUTRAL_SURFACE_APPS]. Everything else that is not a real app - system UI,
     * the intent resolver, keyboards - is already filtered out by [isValidPackageForLocking]
     * before this is reached.
     */
    private fun isNeutralSurface(packageName: String): Boolean {
        if (packageName in AppLockConstants.NEUTRAL_SURFACE_APPS) return true
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

    /**
     * Whether [event] came from a window System UI draws - a notification popping up or updating, the
     * notification shade - rather than from the app it names.
     *
     * Android builds a notification's views with the context of the app that posted it, so their
     * events carry that app's package although System UI draws them. Taken at face value, a text
     * arriving over an unlocked app looks like switching to the messaging app: its lock screen comes
     * up, and the app in front loses its unlock and locks again after it. The window itself still
     * belongs to System UI, which its root view shows.
     *
     * Only a window that is found, of the system type, with a System UI root counts. Anything
     * uncertain is taken as the app itself, as before, so a lock is never skipped on a guess. Floating
     * windows an app draws itself, like chat heads, have that app's root and still count as the app.
     */
    private fun isFromSystemUiWindow(event: AccessibilityEvent): Boolean {
        val window = try {
            windows.firstOrNull { it.id == event.windowId }
        } catch (e: Exception) {
            Log.w(TAG, "Could not look up the window of an event", e)
            null
        } ?: return false
        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) return false
        return window.root?.packageName?.toString() == SYSTEM_UI_PACKAGE
    }

    private fun processPackageLocking(packageName: String, event: AccessibilityEvent) {
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
            // The window and event type say what took over, such as a dialog or another app's screen.
            val takenOverBy = "${event.className}, ${AccessibilityEvent.eventTypeToString(event.eventType)}"
            val surface = if (isNeutral) ", a neutral surface" else ""
            LogUtils.d(
                TAG,
                "Switched from unlocked app $unlockedApp to $currentForegroundPackage ($takenOverBy)$surface."
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
            // Kept in case the prompt goes away unanswered, when this app may need locking after all.
            skippedDuringPromptPackage = packageName
            skippedDuringPromptTrigger = triggeringPackage
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

        override fun onBiometricPromptUnanswered(lockedPackage: String, triggeringPackage: String) {
            handleUnansweredPrompt(lockedPackage, triggeringPackage)
        }
    }

    /**
     * A prompt that had taken the lock screen down for [lockedPackage] went away unanswered, and
     * the lock screen flag is clear again. The app's next event would lock it, but a screen that
     * sits still sends none, so the app in front is checked shortly as well.
     */
    private fun handleUnansweredPrompt(lockedPackage: String, triggeringPackage: String) {
        val skippedPackage = skippedDuringPromptPackage
        val skippedTrigger = skippedDuringPromptTrigger
        skippedDuringPromptPackage = ""
        skippedDuringPromptTrigger = ""

        // With the screen off there is nothing on it to lock, and screen-off resets the rest.
        if (!isScreenInteractive()) return

        AppLockManager.recordBiometricPromptInterrupted(lockedPackage)

        cancelPromptRecheck()
        val recheck = Runnable {
            pendingPromptRecheck = null
            recheckAfterUnansweredPrompt(lockedPackage, triggeringPackage, skippedPackage, skippedTrigger)
        }
        pendingPromptRecheck = recheck
        mainHandler.postDelayed(recheck, PROMPT_RECHECK_DELAY_MS)
    }

    /**
     * Locks the app in front if it is the one the prompt was for, or the last one skipped while the
     * prompt was up - both already passed the trigger exclusions on their way to [checkAndLockApp].
     * Anything else the user moved to is locked, or not, by its own events.
     */
    private fun recheckAfterUnansweredPrompt(
        lockedPackage: String,
        triggeringPackage: String,
        skippedPackage: String,
        skippedTrigger: String
    ) {
        if (!appLockRepository.isProtectionActive() || !isScreenInteractive()) return

        val foreground = lastForegroundPackage
        if (foreground.isEmpty()) return
        val trigger = when (foreground) {
            skippedPackage -> skippedTrigger
            lockedPackage -> triggeringPackage
            else -> return
        }
        if (!isValidPackageForLocking(foreground)) return

        LogUtils.d(TAG, "Checking $foreground again after an unanswered biometric prompt")
        checkAndLockApp(foreground, trigger, System.currentTimeMillis())
    }

    private fun cancelPromptRecheck() {
        pendingPromptRecheck?.let { mainHandler.removeCallbacks(it) }
        pendingPromptRecheck = null
    }

    /**
     * Says how long the screen had been on when it went off, next to the timeout Android was set to
     * at that moment. "Screen timeout by network" writes that setting, so this tells a screen that
     * went dark on its own countdown - and on which value - from one that something turned off.
     *
     * Time on screen is the idle time only if the screen was left alone: a touch restarts Android's
     * countdown and is invisible here, so a screen in use goes off later than this line makes it
     * look. Short is the telling direction.
     */
    private fun logScreenOff() {
        val now = SystemClock.elapsedRealtime()
        val timeoutMs = screenOffTimeoutMs()
        val timeout = if (timeoutMs < 0) "unknown" else "${asSeconds(timeoutMs.toLong())} s"
        val onFor = if (screenOnAt == 0L) -1L else now - screenOnAt
        screenOffAt = now
        screenOnAt = 0L

        if (onFor < 0) {
            LogUtils.d(TAG, "Screen off; screen timeout $timeout, nothing to measure it against")
            return
        }

        val verdict = when {
            timeoutMs < 0 -> "screen timeout unreadable, can't tell what turned it off"
            onFor < timeoutMs - TIMEOUT_MATCH_TOLERANCE_MS ->
                "sooner than the timeout, so the power key or something else turned it off"

            else -> "long enough for the timeout to have run out"
        }
        LogUtils.d(
            TAG,
            "Screen off ${asSeconds(onFor)} s after it came on, screen timeout $timeout: $verdict"
        )
    }

    /**
     * Says how long the screen was off before it came back, and whether the phone locked meanwhile.
     * Nothing this service does can turn the display on, so a screen that comes straight back was
     * woken from outside the app, and the gap tells that apart from someone picking the phone up.
     */
    private fun logScreenOn() {
        val now = SystemClock.elapsedRealtime()
        val offFor = if (screenOffAt == 0L) -1L else now - screenOffAt
        screenOnAt = now
        screenOffAt = 0L

        val lockState = when (getSystemService(KeyguardManager::class.java)?.isDeviceLocked) {
            true -> "phone locked"
            false -> "phone unlocked"
            null -> "lock state unknown"
        }
        if (offFor < 0) {
            LogUtils.d(TAG, "Screen on; no screen off seen to measure from, $lockState")
            return
        }

        val bounce = if (offFor <= SCREEN_BOUNCE_WINDOW_MS) ", straight back on" else ""
        LogUtils.d(TAG, "Screen on after ${asSeconds(offFor)} s off$bounce, $lockState")
    }

    /** Android's screen timeout in milliseconds, or -1 when it can't be read. */
    private fun screenOffTimeoutMs(): Int =
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)

    /** [millis] as seconds to one decimal, with a dot whatever language the phone is in. */
    private fun asSeconds(millis: Long): String =
        String.format(Locale.ROOT, "%.1f", millis / 1000.0)

    /**
     * Takes the lock screen down once the phone's own secure lock is on. Left up, it would sit over
     * the phone's lock screen, showing the locked app's name and taking taps meant for the phone.
     * The phone's lock covers the app instead, and screen-off has already cleared the lock state, so
     * the app locks again from its own events once the phone is unlocked, like any app after screen
     * off. While the phone isn't locked (a lock delay, or a swipe-only or no screen lock), the lock
     * screen stays up, since nothing else covers the app.
     */
    private fun takeDownLockScreenIfPhoneLocked() {
        if (getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true) return
        mainHandler.post { overlayManager?.removeOverlay() }
    }

    // Unknown counts as on, so a failed lookup errs towards locking.
    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive != false

    private fun showLockScreenOverlay(
        packageName: String,
        triggeringPackage: String,
        autoPromptBiometrics: Boolean = true
    ) {
        // A lock screen returning from a cancelled prompt has to get through while the flag is
        // still set - it is the same lock session, not a second one. A new session claims the flag
        // here rather than in the post below: events for one app can arrive in a burst, and each
        // would otherwise still see it clear and open its own lock screen and prompt.
        if (autoPromptBiometrics && !AppLockManager.isLockScreenShown.compareAndSet(false, true)) return

        if (autoPromptBiometrics) {
            // A new lock session: what an earlier prompt skipped is no longer this one's to lock.
            skippedDuringPromptPackage = ""
            skippedDuringPromptTrigger = ""
        }

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
                if (AppLockManager.shouldAutoPromptBiometrics(packageName)) {
                    LogUtils.d(TAG, "Auto-prompting biometrics for: $packageName")
                    startBiometricPrompt(packageName, triggeringPackage)
                } else {
                    LogUtils.d(
                        TAG,
                        "Lock screen for $packageName waits for a tap: its prompt was interrupted twice"
                    )
                }
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

    /**
     * Our name in the phone's language, the one Settings and the package installer show even when
     * the app has a language of its own. Read again only when the phone's locales change. Null when
     * it can't be read, which leaves the checks with the app-language name alone, as before.
     */
    private fun phoneLanguageLabel(): String? {
        return try {
            val labelRes = applicationInfo.labelRes
            val locales = LocaleManagerCompat.getSystemLocales(this)
            if (labelRes == 0 || locales.isEmpty) return null
            if (locales != phoneLanguageLabelLocales) {
                val configuration = Configuration(resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
                }
                phoneLanguageLabelCache = createConfigurationContext(configuration).getString(labelRes)
                phoneLanguageLabelLocales = locales
            }
            phoneLanguageLabelCache
        } catch (e: Exception) {
            logError("Could not read our name in the phone's language", e)
            null
        }
    }

    private fun CharSequence.containsAnyOf(names: List<String>, ignoreCase: Boolean = false): Boolean =
        names.any { contains(it, ignoreCase) }

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
        guardedPageDescribed = false
        val labels = ownLabels
        val showsOurName = event.text.any { it.containsAnyOf(labels) }
        if (packageName.contains(PACKAGE_INSTALLER_MARKER)) {
            val now = SystemClock.uptimeMillis()
            if (className.contains(PACKAGE_INSTALLER_MARKER)) {
                installerUninstallScreenAt =
                    if (className.contains("Uninstall", ignoreCase = true)) now else 0L
            }
            if (showsOurName) installerOurNameAt = now
        }
        LogUtils.d(TAG, "Anti-uninstall sees $packageName / $className, our name in it: $showsOurName")

        if (isAccessibilityServicePage(event)) {
            Log.d(TAG, "Blocking accessibility service deactivation")
            blockDeactivationAttempt("accessibility settings page")
            return
        }

        if (isOwnUninstallDialog(packageName)) {
            Log.d(TAG, "Blocking the uninstall dialog")
            installerUninstallScreenAt = 0L
            installerOurNameAt = 0L
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
            return
        }
        describePageShowingOurName(root)
    }

    /**
     * Diagnostics for [isOwnAppInfoPage]. For a Settings page that shows our name but isn't matched
     * as App info - an app list, or an App info page an OEM builds differently - logs once per page
     * how Settings built it: every view ID on screen, and which views hold our name. That is what
     * turned up [APP_INFO_HEADER_ID] and [APP_INFO_UNINSTALL_ID] on One UI, and would show a further
     * OEM's ids if the check misses there. View IDs and classes only, never page text, and only
     * while logging is on.
     */
    private fun describePageShowingOurName(root: AccessibilityNodeInfo) {
        if (guardedPageDescribed || !appLockRepository.isLoggingEnabled()) return

        val labels = ownLabels
        val holdingOurName = mutableListOf<String>()
        val ids = sortedSetOf<String>()

        fun visit(node: AccessibilityNodeInfo) {
            val id = node.viewIdResourceName
            if (id != null) ids += id
            if (node.text?.containsAnyOf(labels, ignoreCase = true) == true) {
                holdingOurName += "${id ?: "no id"} (${node.className})"
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i) ?: continue)
            }
        }

        try {
            visit(root)
        } catch (e: Exception) {
            logError("Error describing window content", e)
            return
        }
        if (holdingOurName.isEmpty()) return

        guardedPageDescribed = true
        LogUtils.d(
            TAG,
            "Our name on $guardedPageClass, not matched as App info, in: " +
                    "${holdingOurName.joinToString()}; view IDs on screen: ${ids.joinToString()}"
        )
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
        val labels = ownLabels
        return event.text.any { it.containsAnyOf(labels) }
    }

    /**
     * The package installer asking to uninstall us: an uninstall screen (UninstallerActivity or
     * UninstallLaunch on stock Android) and an event showing our name, within a few seconds of each
     * other in either order - on One UI the dialog's event comes about half a second before its
     * UninstallLaunch screen. Our name alone isn't enough: the installer shows it in the same kind of
     * dialog when installing an update to this app, which must not lock the phone. Fails open: if a
     * phone's installer names its screens differently this doesn't fire, and the log shows the names
     * it used.
     */
    private fun isOwnUninstallDialog(packageName: String): Boolean {
        if (!packageName.contains(PACKAGE_INSTALLER_MARKER)) return false
        val now = SystemClock.uptimeMillis()
        return isRecent(installerUninstallScreenAt, now) && isRecent(installerOurNameAt, now)
    }

    private fun isRecent(at: Long, now: Long): Boolean =
        at != 0L && now - at <= INSTALLER_UNINSTALL_WINDOW_MS

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
     * scroll position that shows it - which locked the phone while merely browsing Settings. Two
     * shapes tell the App info page apart from those lists:
     *
     * - **Our name plus our version.** Settings prints the version on the App info page and never in
     *   an app list. Works across OEMs, but on One UI 8.5 the version is off screen until the page
     *   is scrolled, so this alone missed it.
     * - **Our name in the header, next to an Uninstall button.** From a One UI log: the App info
     *   page holds the app name in [APP_INFO_HEADER_ID] and has an [APP_INFO_UNINSTALL_ID] button;
     *   the apps list has neither. Requiring our name *in the header* keeps another app's App info
     *   page from matching.
     *
     * Fails open. If neither shape is present this does not fire, leaving that page unguarded rather
     * than locking the phone on the wrong screen - and Uninstall and Force stop there are blocked
     * and greyed out by device admin regardless.
     */
    private fun isOwnAppInfoPage(root: AccessibilityNodeInfo): Boolean {
        val labels = ownLabels
        val version = ownVersionName
        var labelSeen = false
        var versionSeen = false
        var labelInHeader = false
        var uninstallButtonSeen = false

        fun visit(node: AccessibilityNodeInfo) {
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (text != null) {
                if (text.containsAnyOf(labels, ignoreCase = true)) {
                    labelSeen = true
                    if (id?.contains(APP_INFO_HEADER_ID) == true) labelInHeader = true
                }
                if (version.isNotEmpty() && text.contains(version)) versionSeen = true
            }
            if (id?.contains(APP_INFO_UNINSTALL_ID) == true) uninstallButtonSeen = true
            for (i in 0 until node.childCount) {
                visit(node.getChild(i) ?: continue)
            }
        }

        try {
            visit(root)
        } catch (e: Exception) {
            logError("Error reading app info page", e)
            return false
        }

        return (versionSeen && labelSeen) || (labelInHeader && uninstallButtonSeen)
    }

    /**
     * True when a guard locked the phone moments ago; otherwise records this lock. Settings often
     * reports a page twice within a few milliseconds, and acting on both repeated Back, the lock and
     * Home, which once turned the screen off, on and off again. Unlocking ends the window, so a page
     * opened after unlocking is guarded again at once.
     */
    private fun isRepeatBlock(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (lastBlockAt != 0L && now - lastBlockAt < BLOCK_REPEAT_WINDOW_MS) return true
        lastBlockAt = now
        return false
    }

    /**
     * Leaves the page and locks the phone. Back comes first, so the page is gone before the lock and
     * unlocking doesn't land on it and lock again. Home waits until after the lock, since stopping
     * the attempt doesn't depend on it.
     */
    @SuppressLint("InlinedApi")
    private fun blockDeactivationAttempt(reason: String) {
        stopGuardedPageChecks()
        if (isRepeatBlock()) return
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
        hasAnyText(root, ownLabels)

    @SuppressLint("InlinedApi")
    private fun blockDeviceAdminDeactivation() {
        try {
            val dpm: DevicePolicyManager? = getSystemService()
            val component = ComponentName(this, DeviceAdmin::class.java)

            if (dpm?.isAdminActive(component) == true) {
                if (isRepeatBlock()) return
                // Same order as blockDeactivationAttempt, without the old 100 ms pause before locking.
                performGlobalAction(GLOBAL_ACTION_BACK)
                PhoneLocker.lockPhone(this, "device admin page")
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(
                    this,
                    R.string.anti_uninstall_action_not_allowed_toast,
                    Toast.LENGTH_LONG
                ).show()
                Log.w(TAG, "Blocked device admin deactivation attempt.")
            }
        } catch (e: Exception) {
            logError("Error blocking device admin deactivation", e)
        }
    }

    /**
     * Whether any of [wanted] appears in the text of [root] or any node under it, ignoring case. The
     * walk stops at the first match, since reading nodes can mean calls into the other app's process.
     */
    private fun hasAnyText(root: AccessibilityNodeInfo, wanted: List<String>): Boolean {
        var found = false

        fun visit(node: AccessibilityNodeInfo) {
            if (node.text?.containsAnyOf(wanted, ignoreCase = true) == true) {
                found = true
                return
            }
            for (i in 0 until node.childCount) {
                if (found) return
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
            if (::mainHandler.isInitialized) {
                stopGuardedPageChecks()
                cancelPromptRecheck()
            }

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
