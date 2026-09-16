package dev.pranav.applock.features.applist.domain

import android.content.pm.ApplicationInfo

/**
 * An installed app together with the name shown for it.
 *
 * Reading that name is a call into the package manager that opens the other app's resources, so it
 * is read once when the list is loaded and carried along, instead of being read again for every
 * sort comparison, search and row.
 */
data class InstalledApp(
    val info: ApplicationInfo,
    val label: String
) {
    val packageName: String get() = info.packageName
}
