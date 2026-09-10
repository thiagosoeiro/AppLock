package dev.pranav.applock.services

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.services.AppLockAccessibilityService.BiometricState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object AppLockConstants {
    val KNOWN_RECENTS_CLASSES = setOf(
        "com.android.systemui.recents.RecentsActivity",
        "com.android.quickstep.RecentsActivity",
        "com.android.systemui.recents.RecentsView",
        "com.android.systemui.recents.RecentsPanelView",
    )

    val EXCLUDED_APPS = setOf(
        "com.android.systemui",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "android.uid.system:1000",
        "com.google.android.googlequicksearchbox",
        "android",
        "com.google.android.gms",
        "com.google.android.webview"
    )

    val ACCESSIBILITY_SETTINGS_CLASSES = setOf(
        "com.android.settings.accessibility.AccessibilitySettings",
        "com.android.settings.accessibility.AccessibilityMenuActivity",
        "com.android.settings.accessibility.AccessibilityShortcutActivity",
        "com.android.settings.Settings\$AccessibilitySettingsActivity"
    )

}

fun Context.isDeviceLocked(): Boolean {
    val keyguardManager = getSystemService(KeyguardManager::class.java)
    return keyguardManager?.isKeyguardLocked ?: false
}

/**
 * The system launcher's package, or "" if it cannot be resolved. Shared by the backends that need
 * to tell "the user went home or opened recents" apart from "the user opened another app".
 */
fun Context.defaultLauncherPackageName(): String {
    return try {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }

        val resolveInfoList = packageManager.queryIntentActivities(
            homeIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )

        val systemLauncher = resolveInfoList.find { resolveInfo ->
            val isSystemApp = (resolveInfo.activityInfo.applicationInfo.flags and
                    ApplicationInfo.FLAG_SYSTEM) != 0
            val isOurApp = resolveInfo.activityInfo.packageName == packageName

            isSystemApp && !isOurApp
        }

        systemLauncher?.activityInfo?.packageName ?: ""
    } catch (e: Exception) {
        LogUtils.e("AppLockManager", "Error getting system default launcher package", e)
        ""
    }
}

@Suppress("DEPRECATION")
fun Context.isServiceRunning(serviceClass: Class<*>): Boolean {
    val manager = getSystemService(ActivityManager::class.java) ?: return false
    return manager.getRunningServices(Int.MAX_VALUE)
        .any { serviceClass.name == it.service.className }
}

object AppLockManager {
    private const val TAG = "AppLockManager"

    var temporarilyUnlockedApp: String = ""
    val appUnlockTimes = ConcurrentHashMap<String, Long>()
    val isLockScreenShown = AtomicBoolean(false)
    var currentBiometricState: AppLockAccessibilityService.BiometricState? = null

    /**
     * Lets the biometric prompt hand control of the lock screen back and forth with whichever
     * service is showing it. Registered by [AppLockAccessibilityService] while it is alive; null
     * when another backend is in charge, since those draw their lock screen as an activity.
     */
    interface LockScreenHost {
        fun showLockScreen(
            packageName: String,
            triggeringPackage: String,
            autoPromptBiometrics: Boolean
        )

        fun hideLockScreen()
    }

    @Volatile
    var lockScreenHost: LockScreenHost? = null

    /**
     * How long an app keeps its unlock after being backgrounded for a neutral surface - the
     * launcher, recents, or a system window. Returning to the same app inside this window is
     * treated as never having left it, which is what makes "Lock immediately" bearable without
     * making it meaningless: hand the phone to someone else and the window has almost always
     * already expired.
     */
    private const val RETURN_GRACE_PERIOD_MS = 5_000L

    /**
     * Unlock durations at or above this are the "Until Screen Off" option rather than a real
     * number of minutes. Screen-off wipes [appUnlockTimes], so the timestamp alone carries it.
     */
    private const val UNTIL_SCREEN_OFF_THRESHOLD = 10_000

    private var pendingReturnApp: String = ""
    private var pendingReturnSince: Long = 0L

    /**
     * Records that [packageName] was backgrounded for a neutral surface and may come straight
     * back. Only the caller can tell a neutral surface from a real app switch, so this must not
     * be called when the user actually opened something else - use [dropPendingReturn] there.
     */
    fun holdUnlockForReturn(packageName: String) {
        if (packageName.isEmpty()) return
        pendingReturnApp = packageName
        pendingReturnSince = System.currentTimeMillis()
        LogUtils.d(TAG, "Holding unlock for $packageName, returnable until +${RETURN_GRACE_PERIOD_MS}ms")
    }

    /**
     * Abandons any pending return. Called once a different app really has the foreground, so the
     * window cannot survive an app switch and let the user bounce back in.
     */
    fun dropPendingReturn() {
        if (pendingReturnApp.isEmpty()) return
        LogUtils.d(TAG, "Dropping pending return for $pendingReturnApp")
        pendingReturnApp = ""
        pendingReturnSince = 0L
    }

    /**
     * Whether [packageName] is the app currently being held for a return. Lets callers leave the
     * hold alone when the held app itself comes back, without consuming it.
     */
    fun isPendingReturn(packageName: String): Boolean =
        packageName.isNotEmpty() && packageName == pendingReturnApp

    /**
     * Restores the unlock if [packageName] is the app we are holding and the window has not
     * expired. Consumes the hold either way, so it can never fire twice.
     */
    fun consumeReturnGrace(packageName: String, now: Long): Boolean {
        if (packageName.isEmpty() || packageName != pendingReturnApp) return false

        val elapsed = now - pendingReturnSince
        pendingReturnApp = ""
        pendingReturnSince = 0L

        if (elapsed !in 0..RETURN_GRACE_PERIOD_MS) {
            LogUtils.d(TAG, "Return grace expired for $packageName (elapsed: ${elapsed}ms)")
            return false
        }

        LogUtils.d(TAG, "Restoring unlock for $packageName (elapsed: ${elapsed}ms)")
        temporarilyUnlockedApp = packageName
        return true
    }

    /**
     * The single answer to "should the lock screen come up for this app right now", shared by all
     * three backends so they cannot drift apart.
     *
     * Deliberately does not consider whether the app is locked at all, whether a lock screen is
     * already showing, or whether biometrics are mid-prompt - those are the caller's to check.
     */
    fun shouldShowLockScreen(
        packageName: String,
        now: Long,
        unlockDurationMinutes: Int
    ): Boolean {
        if (isAppTemporarilyUnlocked(packageName)) return false

        if (consumeReturnGrace(packageName, now)) return false

        val unlockTimestamp = appUnlockTimes[packageName] ?: 0L

        if (unlockDurationMinutes > 0 && unlockTimestamp > 0L) {
            if (unlockDurationMinutes >= UNTIL_SCREEN_OFF_THRESHOLD) {
                temporarilyUnlockedApp = packageName
                return false
            }

            val durationMillis = unlockDurationMinutes.toLong() * 60L * 1000L
            val elapsedMillis = now - unlockTimestamp

            LogUtils.d(
                TAG,
                "Grace period check for $packageName: elapsed=${elapsedMillis}ms, duration=${durationMillis}ms"
            )

            if (elapsedMillis < durationMillis) {
                // Re-adopt the app, otherwise the manager believes nothing is unlocked while it
                // sits in the foreground and never holds the unlock on the next switch away.
                temporarilyUnlockedApp = packageName
                return false
            }

            LogUtils.d(TAG, "Unlock duration expired for $packageName")
        }

        clearAppUnlockState(packageName)
        return true
    }

    private val ALL_APP_LOCK_SERVICES = setOf(
        ShizukuAppLockService::class.java,
        UsageLockService::class.java
    )

    fun unlockApp(packageName: String) {
        temporarilyUnlockedApp = packageName
        appUnlockTimes[packageName] = System.currentTimeMillis()
        LogUtils.d(
            TAG,
            "App $packageName unlocked at timestamp: ${appUnlockTimes[packageName]}, current time: ${System.currentTimeMillis()}"
        )
    }

    fun temporarilyUnlockAppWithBiometrics(packageName: String) {
        unlockApp(packageName)
        reportBiometricAuthFinished()
    }

    fun reportBiometricAuthStarted() {
        currentBiometricState = BiometricState.AUTH_STARTED
    }

    fun reportBiometricAuthFinished() {
        currentBiometricState = BiometricState.IDLE
    }

    fun isAppTemporarilyUnlocked(packageName: String): Boolean =
        temporarilyUnlockedApp == packageName

    fun clearTemporarilyUnlockedApp() {
        temporarilyUnlockedApp = ""
    }

    /**
     * Drops every unlock state at once. Used when protection is switched back on, so an app
     * left unlocked while protection was off does not stay open afterwards.
     */
    fun clearAllUnlockStates() {
        temporarilyUnlockedApp = ""
        appUnlockTimes.clear()
        pendingReturnApp = ""
        pendingReturnSince = 0L
        LogUtils.d(TAG, "Cleared all unlock states")
    }

    fun clearAppUnlockState(packageName: String) {
        if (temporarilyUnlockedApp == packageName) {
            temporarilyUnlockedApp = ""
        }
        appUnlockTimes.remove(packageName)
        if (packageName == pendingReturnApp) {
            pendingReturnApp = ""
            pendingReturnSince = 0L
        }
        LogUtils.d(TAG, "Cleared stale unlock state for $packageName")
    }

    fun stopAllOtherServices(context: Context, excludeService: Class<*>) {
        ALL_APP_LOCK_SERVICES
            .filter { it != excludeService }
            .forEach {
                context.stopService(Intent(context, it))
            }
        LogUtils.d(TAG, "Stopped all main app lock services except ${excludeService.simpleName}.")
    }

}
