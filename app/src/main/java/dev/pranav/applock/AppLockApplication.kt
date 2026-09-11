package dev.pranav.applock

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import dev.pranav.applock.core.broadcast.AutomationReceiver
import dev.pranav.applock.core.network.TrustedNetworkMonitor
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.AppLockRepository
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.sui.Sui
import kotlin.concurrent.thread

class AppLockApplication : Application() {

    lateinit var appLockRepository: AppLockRepository
        private set

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        initializeHiddenApiBypass()
    }

    override fun onCreate() {
        super.onCreate()
        initializeComponents()

        // Bring stored credentials up to the current format before any lock screen checks them.
        try {
            appLockRepository.upgradeStoredCredentials()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upgrade stored credentials", e)
        }

        LogUtils.initialize(this)
        LogUtils.setLoggingEnabled(appLockRepository.isLoggingEnabled())

        // Keep the exported receiver's component state in step with the preference, in case the
        // two drifted apart across a restore or an update.
        AutomationReceiver.setComponentEnabled(this, appLockRepository.isAutomationEnabled())

        // Trust in a Wi-Fi network is never stored, so every start is protected until Android
        // reports the network the phone is on. A failure here must not crash the app on every start,
        // which would stop locking altogether.
        try {
            TrustedNetworkMonitor.refresh(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start trusted Wi-Fi monitoring", e)
        }

        // Purge logs older than 3 days on every app start (run in background to avoid ANR)
        thread(start = true, name = "LogPurge") {
            LogUtils.purgeOldLogs()
        }
    }

    private fun initializeHiddenApiBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
                Log.d(TAG, "Hidden API bypass initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize hidden API bypass", e)
            }
        }
    }

    private fun initializeComponents() {
        try {
            appLockRepository = AppLockRepository(this)
            initializeSui()
            Log.d(TAG, "Application components initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize application components", e)
        }
    }

    private fun initializeSui() {
        try {
            Sui.init(packageName)
            Log.d(TAG, "Sui initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sui", e)
        }
    }

    companion object {
        private const val TAG = "AppLockApplication"
    }
}
