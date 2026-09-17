package dev.pranav.applock.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Repository for managing locked applications and trigger exclusions.
 * Handles all app-related locking functionality.
 */
class LockedAppsRepository(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The names of protected apps by package name, saved while they are installed. An app can be
     * uninstalled outside Secure Folder while its copy inside keeps locking, and from then on this is
     * the only place its name can be read. A file of its own keeps package names from becoming keys
     * among the settings in [PREFS_NAME].
     */
    private val appNames: SharedPreferences =
        context.getSharedPreferences(APP_NAMES_PREFS_NAME, Context.MODE_PRIVATE)

    // Locked Apps Management
    fun getLockedApps(): Set<String> {
        return preferences.getStringSet(KEY_LOCKED_APPS, emptySet())?.toSet() ?: emptySet()
    }

    fun addLockedApp(packageName: String) {
        if (packageName.isBlank()) return
        val updated = getLockedApps() + packageName
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
    }

    fun removeLockedApp(packageName: String) {
        val updated = getLockedApps() - packageName
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
        appNames.edit { remove(packageName) }
    }

    fun isAppLocked(packageName: String): Boolean {
        return getLockedApps().contains(packageName)
    }

    fun clearAllLockedApps() {
        preferences.edit { putStringSet(KEY_LOCKED_APPS, emptySet()) }
        appNames.edit { clear() }
    }

    /** The saved names of protected apps, by package name. */
    fun getLockedAppNames(): Map<String, String> =
        appNames.all.mapNotNull { (packageName, name) ->
            (name as? String)?.let { packageName to it }
        }.toMap()

    /** Saves [names], by package name, writing only the ones that changed. */
    fun saveLockedAppNames(names: Map<String, String>) {
        val changed = names.filter { (packageName, name) ->
            name.isNotBlank() && appNames.getString(packageName, null) != name
        }
        if (changed.isEmpty()) return
        appNames.edit { changed.forEach { (packageName, name) -> putString(packageName, name) } }
    }

    // Trigger Exclusions Management
    fun getTriggerExcludedApps(): Set<String> {
        return preferences.getStringSet(KEY_TRIGGER_EXCLUDED_APPS, emptySet())?.toSet()
            ?: emptySet()
    }

    fun addTriggerExcludedApp(packageName: String) {
        if (packageName.isBlank()) return
        val updated = getTriggerExcludedApps() + packageName
        preferences.edit { putStringSet(KEY_TRIGGER_EXCLUDED_APPS, updated) }
    }

    fun removeTriggerExcludedApp(packageName: String) {
        val updated = getTriggerExcludedApps() - packageName
        preferences.edit { putStringSet(KEY_TRIGGER_EXCLUDED_APPS, updated) }
    }

    fun isAppTriggerExcluded(packageName: String): Boolean {
        return getTriggerExcludedApps().contains(packageName)
    }

    fun clearAllTriggerExclusions() {
        preferences.edit { putStringSet(KEY_TRIGGER_EXCLUDED_APPS, emptySet()) }
    }

    // Anti-Uninstall Apps Management
    fun getAntiUninstallApps(): Set<String> {
        return preferences.getStringSet(KEY_ANTI_UNINSTALL_APPS, emptySet())?.toSet() ?: emptySet()
    }

    fun addAntiUninstallApp(packageName: String) {
        if (packageName.isBlank()) return
        val updated = getAntiUninstallApps() + packageName
        preferences.edit { putStringSet(KEY_ANTI_UNINSTALL_APPS, updated) }
    }

    fun removeAntiUninstallApp(packageName: String) {
        val updated = getAntiUninstallApps() - packageName
        preferences.edit { putStringSet(KEY_ANTI_UNINSTALL_APPS, updated) }
    }

    fun isAppAntiUninstall(packageName: String): Boolean {
        return getAntiUninstallApps().contains(packageName)
    }

    fun clearAllAntiUninstallApps() {
        preferences.edit { putStringSet(KEY_ANTI_UNINSTALL_APPS, emptySet()) }
    }

    // Bulk operations
    fun addMultipleLockedApps(packageNames: Set<String>) {
        val validPackageNames = packageNames.filter { it.isNotBlank() }.toSet()
        if (validPackageNames.isEmpty()) return
        val updated = getLockedApps() + validPackageNames
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
    }

    fun removeMultipleLockedApps(packageNames: Set<String>) {
        val updated = getLockedApps() - packageNames
        preferences.edit { putStringSet(KEY_LOCKED_APPS, updated) }
        appNames.edit { packageNames.forEach { remove(it) } }
    }

    companion object {
        private const val PREFS_NAME = "app_lock_prefs"
        private const val APP_NAMES_PREFS_NAME = "locked_app_names"
        private const val KEY_LOCKED_APPS = "locked_apps"
        private const val KEY_TRIGGER_EXCLUDED_APPS = "trigger_excluded_apps"
        private const val KEY_ANTI_UNINSTALL_APPS = "anti_uninstall_apps"
    }
}
