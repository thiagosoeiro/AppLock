package dev.pranav.applock.core.broadcast

import android.app.admin.DeviceAdminInfo
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.content.getSystemService

class DeviceAdmin : DeviceAdminReceiver() {
    companion object {
        private const val PREFS_NAME = "dev.pranav.applock.admin_prefs"
        private const val KEY_PASSWORD_VERIFIED = "password_verified"

        /**
         * True when our admin is active and holds the force-lock policy, so
         * [DevicePolicyManager.lockNow] can lock the phone.
         */
        fun hasForceLock(context: Context): Boolean {
            val dpm = context.getSystemService<DevicePolicyManager>() ?: return false
            val component = ComponentName(context, DeviceAdmin::class.java)
            return try {
                dpm.isAdminActive(component) &&
                        dpm.hasGrantedPolicy(component, DeviceAdminInfo.USES_POLICY_FORCE_LOCK)
            } catch (_: SecurityException) {
                // The admin was removed between the two calls.
                false
            }
        }
    }

    /**
     * An active admin is itself what blocks uninstalling the app. Don't call setUninstallBlocked
     * here: only a device or profile owner may, so it throws and crashes the app.
     */
    override fun onEnabled(context: Context, intent: android.content.Intent) {
        super.onEnabled(context, intent)
        context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("anti_uninstall", true)
        }
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        super.onDisabled(context, intent)
        context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE).edit {
            putBoolean("anti_uninstall", false)
        }
    }

    fun setPasswordVerified(context: Context, verified: Boolean) {
        getSharedPreferences(context).edit { putBoolean(KEY_PASSWORD_VERIFIED, verified) }
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
