package dev.pranav.applock.features.applist.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.features.applist.domain.AppSearchManager
import dev.pranav.applock.features.applist.domain.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appSearchManager = AppSearchManager(application)
    private val appLockRepository = AppLockRepository(application)

    private val createdAt = SystemClock.elapsedRealtime()

    private val _lockedPackages = MutableStateFlow(appLockRepository.getLockedApps())

    /**
     * The apps to draw, by package name. The protected ones land here first, on their own, so the
     * screen can be drawn without waiting for every package on the phone; the full list is merged
     * in when it arrives. Null until even the protected apps are ready.
     */
    private val _entriesByPackage = MutableStateFlow<Map<String, InstalledApp>?>(null)

    /** Every installed app, sorted, for the "+" sheet. Null until the background load finishes. */
    private val _allApps = MutableStateFlow<List<InstalledApp>?>(null)

    /**
     * The protected apps. Null means still loading, which keeps the "no protected apps" empty state
     * from flashing between the spinner and the list.
     */
    val lockedAppsFlow: StateFlow<List<InstalledApp>?> =
        combine(_entriesByPackage, _lockedPackages) { entries, locked ->
            entries?.let { byPackage ->
                locked.mapNotNull(byPackage::get).sortedWith(appSearchManager.labelOrder)
            }
        }.flowOn(Dispatchers.Default).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        )

    /**
     * The apps that can still be protected, for the "+" sheet. Null while the full list is loading.
     * The list arrives sorted, so this only filters.
     */
    val unlockedAppsFlow: StateFlow<List<InstalledApp>?> =
        combine(_allApps, _lockedPackages) { apps, locked ->
            apps?.filterNot { it.packageName in locked }
        }.flowOn(Dispatchers.Default).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        )

    init {
        loadApplications()
    }

    private fun loadApplications() {
        viewModelScope.launch {
            // The protected apps first, by name, so the main screen can be drawn right away.
            val lockedByPackage = try {
                val savedNames = withContext(Dispatchers.IO) { appLockRepository.getLockedAppNames() }
                appSearchManager.loadApps(_lockedPackages.value, savedNames)
                    .associateBy { it.packageName }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load the protected apps", e)
                emptyMap()
            }
            _entriesByPackage.value = lockedByPackage
            logReady("Protected apps", lockedByPackage.size, "apps")

            saveNames(lockedByPackage.values)
            lockedByPackage.values.filterNot { it.isInstalled }.forEach { app ->
                val shownBy = if (app.label != app.packageName) "its saved name" else "its package name"
                LogUtils.d(
                    TAG,
                    "${app.packageName} is protected but not installed outside Secure Folder, shown by $shownBy"
                )
            }

            // Then every package, which only the "+" sheet needs, while the screen is already up.
            val allApps = try {
                appSearchManager.loadApps(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load the installed apps", e)
                emptyList()
            }
            // Keeping the protected entries covers a locked app the full list leaves out.
            _entriesByPackage.value = lockedByPackage + allApps.associateBy { it.packageName }
            _allApps.value = allApps
            logReady("Full app list", allApps.size, "packages")
        }
    }

    fun lockApps(packageNames: List<String>) {
        val names = packageNames.toSet()
        appLockRepository.addMultipleLockedApps(names)
        _allApps.value?.filter { it.packageName in names }?.let(::saveNames)
        _lockedPackages.value = appLockRepository.getLockedApps()
    }

    fun unlockApp(packageName: String) {
        appLockRepository.removeLockedApp(packageName)
        _lockedPackages.value = appLockRepository.getLockedApps()
    }

    /**
     * Saves the names of the installed apps among [apps]. The list shows a saved name once an app is
     * left only inside Secure Folder, where its name can't be read.
     */
    private fun saveNames(apps: Collection<InstalledApp>) {
        appLockRepository.saveLockedAppNames(
            apps.filter { it.isInstalled }.associate { it.packageName to it.label }
        )
    }

    /**
     * Times the two waits the main screen has, counted from this view model being created: the
     * protected apps appearing, and the full list behind the "+" button being ready. Written only
     * while Settings → Logging is on, and read from Settings → Export audit logs.
     */
    private fun logReady(what: String, count: Int, unit: String) {
        val elapsed = SystemClock.elapsedRealtime() - createdAt
        LogUtils.d(TAG, "$what ready in $elapsed ms ($count $unit)")
    }
}

private const val TAG = "MainViewModel"
