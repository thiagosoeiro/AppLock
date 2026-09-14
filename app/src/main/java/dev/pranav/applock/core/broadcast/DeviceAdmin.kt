package dev.pranav.applock.core.broadcast

import android.app.admin.DeviceAdminInfo
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.content.getSystemService
import dev.pranav.applock.R
import dev.pranav.applock.core.utils.PhoneLocker

class DeviceAdmin : DeviceAdminReceiver() {
    companion object {
        private const val PREFS_NAME = "dev.pranav.applock.admin_prefs"
        private const val KEY_PASSWORD_VERIFIED = "password_verified"

        // How long after the app opens the grant page itself the anti-uninstall guard leaves it be.
        private const val OWN_GRANT_WINDOW_MS = 60_000L

        @Volatile
        private var ownGrantRequestedAt = 0L

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

        /**
         * Opens Settings' page for granting our admin. Android keeps only the policies an admin was
         * granted when it was activated, so an admin granted before force-lock was added lacks it;
         * for an active admin the same request opens a page that adds the missing policy, with no
         * need to deactivate first.
         *
         * While our admin is active, that page is the one the anti-uninstall guard bounces, so note
         * that the app opened it itself; see [isOwnGrantPending].
         */
        fun requestGrant(context: Context) {
            ownGrantRequestedAt = SystemClock.elapsedRealtime()
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(context, DeviceAdmin::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.main_screen_device_admin_explanation)
                )
            }
            context.startActivity(intent)
        }

        /**
         * True for a minute after [requestGrant], and only while force-lock is still missing, so the
         * guard is back as soon as the grant goes through. Only screens behind the PIN call
         * [requestGrant]. The admin callbacks ignore this: Deactivate still locks the phone.
         */
        fun isOwnGrantPending(context: Context): Boolean {
            val requestedAt = ownGrantRequestedAt
            if (requestedAt == 0L ||
                SystemClock.elapsedRealtime() - requestedAt > OWN_GRANT_WINDOW_MS
            ) {
                return false
            }
            return !hasForceLock(context)
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

    /**
     * Android calls this when someone taps Deactivate on our device admin page, before it removes
     * anything. With anti-uninstall on, lock the phone on that tap and hand back a warning: Android
     * then asks for OK in a second dialog, which is now behind the lock screen. The accessibility
     * guard only sees the page once it is drawn and can lose to a fast tap; this runs on the tap.
     *
     * The warning is the disguised admin description, so it gives nothing away. App info's one-tap
     * "Deactivate and uninstall" skips this call; [onDisabled] covers that route.
     */
    override fun onDisableRequested(context: Context, intent: android.content.Intent): CharSequence? {
        if (!isAntiUninstallOn(context)) return super.onDisableRequested(context, intent)

        PhoneLocker.lockPhone(context, "device admin deactivation requested")
        return context.getString(R.string.device_admin_description)
    }

    /**
     * Android calls this whenever our admin is removed, including App info's one-tap "Deactivate and
     * uninstall", where it waits for this before force-stopping the app and opening the uninstall
     * dialog. Turning anti-uninstall off with the PIN clears the flag first, so reaching this with
     * the flag still on means someone got past the guards. Lock the phone, and leave the flag on:
     * clearing it would switch off every other guard, and the main screen asks for the admin back.
     */
    override fun onDisabled(context: Context, intent: android.content.Intent) {
        super.onDisabled(context, intent)
        if (isAntiUninstallOn(context)) {
            PhoneLocker.lockPhone(context, "device admin removed")
        }
    }

    // The anti-uninstall flag, from the preferences onEnabled writes it to.
    private fun isAntiUninstallOn(context: Context): Boolean =
        context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE)
            .getBoolean("anti_uninstall", false)

    fun setPasswordVerified(context: Context, verified: Boolean) {
        getSharedPreferences(context).edit { putBoolean(KEY_PASSWORD_VERIFIED, verified) }
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
