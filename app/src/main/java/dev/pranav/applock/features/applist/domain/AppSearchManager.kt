package dev.pranav.applock.features.applist.domain

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

class AppSearchManager(private val context: Context) {

    /**
     * Orders names the way the current language does, so "eBay" sits with the E's and "Álbum" with
     * the A's. Comparing the strings directly puts both after Z.
     */
    val labelOrder: Comparator<InstalledApp> =
        Collator.getInstance(context.resources.configuration.locales[0]).let { collator ->
            Comparator { a, b -> collator.compare(a.label, b.label) }
        }

    /**
     * Every installed app, sorted. This is the expensive one: it reads the name of every package on
     * the phone, several hundred of them, so it runs off the main thread and nothing that has to be
     * drawn first should wait for it.
     */
    suspend fun loadApps(includeSystemApps: Boolean = false): List<InstalledApp> {
        return withContext(Dispatchers.IO) {
            val apps = if (includeSystemApps) {
                // Load all apps including system apps
                context.packageManager.getInstalledApplications(0)
                    .filter { it.packageName != context.packageName }
            } else {
                // Load only user-installed apps with launcher activities
                val launcherApps =
                    context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.getActivityList(null, Process.myUserHandle())
                    .mapNotNull { it.applicationInfo }
                    .filter { it.enabled && it.packageName != context.packageName }
            }

            apps.distinctBy { it.packageName }
                .map { it.toInstalledApp() }
                .sortedWith(labelOrder)
        }
    }

    /**
     * Only the apps named, sorted, for the protected list on the main screen. Asking for a handful
     * of packages by name costs a fraction of loading every package to then throw almost all of
     * them away.
     *
     * Every name gets an entry. One that isn't installed outside Secure Folder can still lock a copy
     * inside, which only Secure Folder itself can see, so it is kept as a stand-in labeled with its
     * name from [savedNames], or its package name when none was saved.
     */
    suspend fun loadApps(
        packageNames: Set<String>,
        savedNames: Map<String, String>
    ): List<InstalledApp> {
        return withContext(Dispatchers.IO) {
            packageNames
                .filter { it != context.packageName }
                .map { packageName ->
                    runCatching {
                        context.packageManager.getApplicationInfo(packageName, 0).toInstalledApp()
                    }.getOrElse {
                        InstalledApp(
                            ApplicationInfo().apply { this.packageName = packageName },
                            savedNames[packageName] ?: packageName
                        )
                    }
                }
                .sortedWith(labelOrder)
        }
    }

    private fun ApplicationInfo.toInstalledApp() =
        InstalledApp(this, loadLabel(context.packageManager).toString())
}
