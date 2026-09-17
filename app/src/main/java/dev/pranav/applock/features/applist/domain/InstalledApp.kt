package dev.pranav.applock.features.applist.domain

import android.content.pm.ApplicationInfo

/**
 * An installed app together with the name shown for it.
 *
 * Reading that name is a call into the package manager that opens the other app's resources, so it
 * is read once when the list is loaded and carried along, instead of being read again for every
 * sort comparison, search and row.
 *
 * A protected app that is no longer installed outside Secure Folder is still listed, since its copy
 * inside keeps locking. It stands in as an empty [ApplicationInfo] carrying only its package name,
 * labeled with its saved name, and [isInstalled] is false.
 */
data class InstalledApp(
    val info: ApplicationInfo,
    val label: String
) {
    val packageName: String get() = info.packageName

    /** Whether the app is installed outside Secure Folder, as Android marks it on [info]. */
    val isInstalled: Boolean get() = (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
}
