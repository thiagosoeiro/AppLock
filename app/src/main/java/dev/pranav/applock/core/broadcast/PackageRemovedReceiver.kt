package dev.pranav.applock.core.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository

/**
 * Takes an uninstalled app off the protected list, but only once it is gone from the whole phone.
 *
 * Uninstalling an app outside Secure Folder leaves its copy inside, and that copy keeps locking, so
 * its entry has to stay. Nothing this app can query sees inside Secure Folder, but Android says in
 * the broadcast itself whether the app was removed for every user. Only a clear yes removes the
 * entry. If Android doesn't say, the entry stays, and the main screen still lists it for unprotecting.
 *
 * ACTION_PACKAGE_FULLY_REMOVED is a protected broadcast, so only the system can send it.
 */
class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val packageName = intent.data?.schemeSpecificPart ?: return

        try {
            val repository = context.appLockRepository()
            if (!repository.isAppLocked(packageName)) return

            when {
                !intent.hasExtra(EXTRA_REMOVED_FOR_ALL_USERS) -> LogUtils.d(
                    TAG,
                    "Kept $packageName after its uninstall: Android didn't say whether other copies remain"
                )

                !intent.getBooleanExtra(EXTRA_REMOVED_FOR_ALL_USERS, false) -> LogUtils.d(
                    TAG,
                    "Kept $packageName after its uninstall: a copy remains in another profile, such as Secure Folder"
                )

                else -> {
                    repository.removeLockedApp(packageName)
                    LogUtils.d(TAG, "Removed $packageName from the protected list: uninstalled everywhere")
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Handling the uninstall of $packageName failed", e)
        }
    }

    companion object {
        private const val TAG = "PackageRemovedReceiver"

        /**
         * Whether the package is now gone for every user on the phone. Android puts it on its
         * package removal broadcasts, but the constant is hidden in [Intent], so its value is
         * written out here.
         */
        private const val EXTRA_REMOVED_FOR_ALL_USERS = "android.intent.extra.REMOVED_FOR_ALL_USERS"
    }
}
