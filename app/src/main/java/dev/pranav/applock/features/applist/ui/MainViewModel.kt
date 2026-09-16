package dev.pranav.applock.features.applist.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.features.applist.domain.AppSearchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appSearchManager = AppSearchManager(application)
    private val appLockRepository = AppLockRepository(application)

    private val createdAt = SystemClock.elapsedRealtime()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _allApps = MutableStateFlow<Set<ApplicationInfo>>(emptySet())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _lockedApps = MutableStateFlow<Set<String>>(emptySet())

    private val _debouncedQuery = MutableStateFlow("")

    val lockedAppsFlow: StateFlow<List<ApplicationInfo>> =
        combine(_allApps, _lockedApps, _debouncedQuery) { apps, locked, query ->
            apps.filter { it.packageName in locked }
                .filter { it.matchesQuery(query) }
                .sortedBy { it.loadLabel(getApplication<Application>().packageManager).toString() }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val unlockedAppsFlow: StateFlow<List<ApplicationInfo>> =
        combine(_allApps, _lockedApps, _debouncedQuery) { apps, locked, query ->
            apps.filterNot { it.packageName in locked }
                .filter { it.matchesQuery(query) }
                .sortedBy { it.loadLabel(getApplication<Application>().packageManager).toString() }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    private fun ApplicationInfo.matchesQuery(query: String): Boolean {
        if (query.isBlank()) return true
        return loadLabel(getApplication<Application>().packageManager).toString()
            .contains(query, ignoreCase = true)
    }

    init {
        loadAllApplications()
        loadLockedApps()

        viewModelScope.launch {
            _searchQuery
                .debounce(100L)
                .collect { query ->
                    _debouncedQuery.value = query
                }
        }
    }

    private fun loadAllApplications() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val apps = withContext(Dispatchers.IO) {
                    appSearchManager.loadApps(true)
                }
                _allApps.value = apps
                logReadyTimes(apps)
            } catch (e: Exception) {
                e.printStackTrace()
                _allApps.value = emptySet()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadLockedApps() {
        _lockedApps.value = appLockRepository.getLockedApps()
    }

    fun lockApps(packageNames: List<String>) {
        appLockRepository.addMultipleLockedApps(packageNames.toSet())
        _lockedApps.value = appLockRepository.getLockedApps()
    }

    fun unlockApp(packageName: String) {
        appLockRepository.removeLockedApp(packageName)
        _lockedApps.value = appLockRepository.getLockedApps()
    }

    /**
     * Times the two waits the main screen has, counted from this view model being created: the
     * protected apps appearing, and the full list behind the "+" button being ready. Written only
     * while Settings → Logging is on, and read from Settings → Export audit logs.
     */
    private fun logReadyTimes(apps: Set<ApplicationInfo>) {
        val elapsed = SystemClock.elapsedRealtime() - createdAt
        val locked = apps.count { it.packageName in _lockedApps.value }
        LogUtils.d(TAG, "Protected apps ready in $elapsed ms ($locked apps)")
        LogUtils.d(TAG, "Full app list ready in $elapsed ms (${apps.size} packages)")
    }
}

private const val TAG = "MainViewModel"
