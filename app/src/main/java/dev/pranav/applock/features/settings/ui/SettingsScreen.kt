package dev.pranav.applock.features.settings.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import dev.pranav.applock.R
import dev.pranav.applock.core.broadcast.AutomationReceiver
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.navigation.Screen
import dev.pranav.applock.core.network.TrustedNetworkMonitor
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.canAuthenticateBiometrics
import dev.pranav.applock.core.utils.hasUsagePermission
import dev.pranav.applock.core.utils.isAccessibilityServiceEnabled
import dev.pranav.applock.core.utils.openAccessibilitySettings
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.features.admin.AdminDisableActivity
import dev.pranav.applock.services.ShizukuAppLockService
import dev.pranav.applock.services.UsageLockService
import dev.pranav.applock.ui.icons.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val appLockRepository = remember { AppLockRepository(context) }

    var showUnlockTimeDialog by remember { mutableStateOf(false) }

    val shizukuPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_screen_shizuku_permission_granted),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.settings_screen_shizuku_permission_required_desc),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    var autoUnlock by remember { mutableStateOf(appLockRepository.isAutoUnlockEnabled()) }
    var useMaxBrightness by remember { mutableStateOf(appLockRepository.shouldUseMaxBrightness()) }
    var useBiometricAuth by remember { mutableStateOf(appLockRepository.isBiometricAuthEnabled()) }
    var unlockTimeDuration by remember { mutableIntStateOf(appLockRepository.getUnlockTimeDuration()) }
    var antiUninstallEnabled by remember { mutableStateOf(appLockRepository.isAntiUninstallEnabled()) }
    var disableHapticFeedback by remember { mutableStateOf(appLockRepository.shouldDisableHaptics()) }
    var loggingEnabled by remember { mutableStateOf(appLockRepository.isLoggingEnabled()) }
    var automationEnabled by remember { mutableStateOf(appLockRepository.isAutomationEnabled()) }
    var automationToken by remember { mutableStateOf(appLockRepository.getAutomationToken()) }
    var showAutomationDialog by remember { mutableStateOf(false) }
    var trustedWifiEnabled by remember { mutableStateOf(appLockRepository.isTrustedWifiEnabled()) }
    var trustedWifiSsids by remember { mutableStateOf(appLockRepository.getTrustedWifiSsids()) }
    var showTrustedNetworksDialog by remember { mutableStateOf(false) }
    var showBackgroundLocationDialog by remember { mutableStateOf(false) }
    val trustedNetworkState by TrustedNetworkMonitor.state.collectAsState()
    var hasLocationAccess by remember {
        mutableStateOf(
            TrustedNetworkMonitor.hasLocationPermission(context) &&
                    TrustedNetworkMonitor.hasBackgroundLocationPermission(context)
        )
    }
    var locationEnabled by remember { mutableStateOf(TrustedNetworkMonitor.isLocationEnabled(context)) }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var showDeviceAdminDialog by remember { mutableStateOf(false) }
    var showAccessibilityDialog by remember { mutableStateOf(false) }

    val isBiometricAvailable = remember { context.canAuthenticateBiometrics() }

    // Anti-uninstall also changes away from this screen - granting device admin turns it on, and
    // AdminDisableActivity turns it off - so follow the stored flag rather than reading it once.
    DisposableEffect(Unit) {
        val prefs = context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "anti_uninstall") {
                antiUninstallEnabled = appLockRepository.isAntiUninstallEnabled()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Location access and the Location switch change in Android's settings, away from this screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationAccess = TrustedNetworkMonitor.hasLocationPermission(context) &&
                        TrustedNetworkMonitor.hasBackgroundLocationPermission(context)
                locationEnabled = TrustedNetworkMonitor.isLocationEnabled(context)
                if (trustedWifiEnabled) TrustedNetworkMonitor.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun enableTrustedWifi() {
        appLockRepository.setTrustedWifiEnabled(true)
        trustedWifiEnabled = true
        hasLocationAccess = true
        TrustedNetworkMonitor.refresh(context)
        if (trustedWifiSsids.isEmpty()) showTrustedNetworksDialog = true
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            enableTrustedWifi()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.settings_screen_trusted_wifi_background_denied),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        when {
            !TrustedNetworkMonitor.hasLocationPermission(context) -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_trusted_wifi_location_denied),
                    Toast.LENGTH_LONG
                ).show()
            }

            TrustedNetworkMonitor.hasBackgroundLocationPermission(context) -> enableTrustedWifi()

            // From Android 11, "Allow all the time" is only offered on its own settings page.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                showBackgroundLocationDialog = true
            }

            else -> backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    if (showUnlockTimeDialog) {
        UnlockTimeDurationDialog(
            currentDuration = unlockTimeDuration,
            onDismiss = { showUnlockTimeDialog = false },
            onConfirm = { newDuration ->
                unlockTimeDuration = newDuration
                appLockRepository.setUnlockTimeDuration(newDuration)
                showUnlockTimeDialog = false
            }
        )
    }

    if (showAutomationDialog) {
        AutomationTokenDialog(
            token = automationToken.orEmpty(),
            onCopy = {
                copyTokenToClipboard(context, automationToken.orEmpty())
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_automation_copied),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onRegenerate = {
                automationToken = appLockRepository.regenerateAutomationToken()
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_automation_regenerated),
                    Toast.LENGTH_LONG
                ).show()
            },
            onDismiss = { showAutomationDialog = false }
        )
    }

    if (showBackgroundLocationDialog) {
        AlertDialog(
            onDismissRequest = { showBackgroundLocationDialog = false },
            title = { Text(stringResource(R.string.settings_screen_trusted_wifi_background_dialog_title)) },
            text = { Text(stringResource(R.string.settings_screen_trusted_wifi_background_dialog_text)) },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showBackgroundLocationDialog = false
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                ) {
                    Text(stringResource(R.string.settings_screen_trusted_wifi_background_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundLocationDialog = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showTrustedNetworksDialog) {
        TrustedNetworksDialog(
            trustedSsids = trustedWifiSsids,
            currentSsid = trustedNetworkState.currentSsid,
            locationEnabled = locationEnabled,
            onAdd = { ssid ->
                appLockRepository.addTrustedWifiSsid(ssid)
                trustedWifiSsids = appLockRepository.getTrustedWifiSsids()
                TrustedNetworkMonitor.refresh(context)
            },
            onRemove = { ssid ->
                appLockRepository.removeTrustedWifiSsid(ssid)
                trustedWifiSsids = appLockRepository.getTrustedWifiSsids()
                TrustedNetworkMonitor.refresh(context)
            },
            onOpenLocationSettings = {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            },
            onDismiss = { showTrustedNetworksDialog = false }
        )
    }

    if (showPermissionDialog) {
        PermissionRequiredDialog(
            onDismiss = { showPermissionDialog = false },
            onConfirm = {
                showPermissionDialog = false
                showDeviceAdminDialog = true
            }
        )
    }

    if (showDeviceAdminDialog) {
        DeviceAdminDialog(
            onDismiss = { showDeviceAdminDialog = false },
            onConfirm = {
                showDeviceAdminDialog = false
                DeviceAdmin.requestGrant(context)
            }
        )
    }

    if (showAccessibilityDialog) {
        AccessibilityDialog(
            onDismiss = { showAccessibilityDialog = false },
            onConfirm = {
                showAccessibilityDialog = false
                openAccessibilitySettings(context)
                if (!DeviceAdmin.hasForceLock(context)) {
                    showDeviceAdminDialog = true
                }
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_screen_back_cd)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val packageInfo = remember {
                    try {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    } catch (_: Exception) {
                        null
                    }
                }
                val versionName = packageInfo?.versionName ?: "Unknown"
                Text(
                    text = stringResource(R.string.settings_screen_version_template, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                )
            }

            item {
                SectionTitle(text = stringResource(R.string.settings_screen_lock_screen_customization_title))
            }

            item {
                SettingsGroup(
                    items = listOf(
                        ToggleSettingItem(
                            icon = BrightnessHigh,
                            title = stringResource(R.string.settings_screen_max_brightness_title),
                            subtitle = stringResource(R.string.settings_screen_max_brightness_desc),
                            checked = useMaxBrightness,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                useMaxBrightness = isChecked
                                appLockRepository.setUseMaxBrightness(isChecked)
                            }
                        ),
                        ToggleSettingItem(
                            icon = if (useBiometricAuth) Fingerprint else FingerprintOff,
                            title = stringResource(R.string.settings_screen_biometric_auth_title),
                            subtitle = if (isBiometricAvailable)
                                stringResource(R.string.settings_screen_biometric_auth_desc_available)
                            else
                                stringResource(R.string.settings_screen_biometric_auth_desc_unavailable),
                            checked = useBiometricAuth && isBiometricAvailable,
                            enabled = isBiometricAvailable,
                            onCheckedChange = { isChecked ->
                                useBiometricAuth = isChecked
                                appLockRepository.setBiometricAuthEnabled(isChecked)
                            }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.Vibration,
                            title = stringResource(R.string.settings_screen_haptic_feedback_title),
                            subtitle = stringResource(R.string.settings_screen_haptic_feedback_desc),
                            checked = disableHapticFeedback,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                disableHapticFeedback = isChecked
                                appLockRepository.setDisableHaptics(isChecked)
                            }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.ShieldMoon,
                            title = stringResource(R.string.settings_screen_auto_unlock_title),
                            subtitle = stringResource(R.string.settings_screen_auto_unlock_desc),
                            checked = autoUnlock,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                autoUnlock = isChecked
                                appLockRepository.setAutoUnlockEnabled(isChecked)
                            }
                        )
                    )
                )
            }

            item {
                SectionTitle(text = stringResource(R.string.settings_screen_security_title))
            }

            item {
                SettingsGroup(
                    items = listOf(
                        ActionSettingItem(
                            icon = Icons.Default.Lock,
                            title = stringResource(R.string.settings_screen_change_pin_title),
                            subtitle = stringResource(R.string.settings_screen_change_pin_desc),
                            onClick = { navController.navigate(Screen.ChangePassword.route) }
                        ),
                        ActionSettingItem(
                            icon = Timer,
                            title = stringResource(R.string.settings_screen_unlock_duration_title),
                            subtitle = if (unlockTimeDuration > 0) {
                                if (unlockTimeDuration > 10_000) "Until screen off"
                                else stringResource(
                                    R.string.settings_screen_unlock_duration_summary_minutes,
                                    unlockTimeDuration
                                )
                            } else stringResource(R.string.settings_screen_unlock_duration_summary_immediate),
                            onClick = { showUnlockTimeDialog = true }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.Lock,
                            title = stringResource(R.string.settings_screen_anti_uninstall_title),
                            subtitle = stringResource(R.string.settings_screen_anti_uninstall_desc),
                            checked = antiUninstallEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    // An admin granted before force-lock was added needs granting again.
                                    val hasDeviceAdmin = DeviceAdmin.hasForceLock(context)
                                    val hasAccessibility = context.isAccessibilityServiceEnabled()

                                    when {
                                        !hasDeviceAdmin && !hasAccessibility -> {
                                            showPermissionDialog = true
                                        }
                                        !hasDeviceAdmin -> {
                                            showDeviceAdminDialog = true
                                        }
                                        !hasAccessibility -> {
                                            showAccessibilityDialog = true
                                        }
                                        else -> {
                                            antiUninstallEnabled = true
                                            appLockRepository.setAntiUninstallEnabled(true)
                                        }
                                    }
                                } else {
                                    context.startActivity(
                                        Intent(context, AdminDisableActivity::class.java)
                                    )
                                }
                            }
                        )
                    )
                )
            }

            item {
                SectionTitle(text = stringResource(R.string.settings_screen_trusted_wifi_title))
            }

            item {
                SettingsGroup(
                    items = listOf(
                        ToggleSettingItem(
                            icon = Icons.Default.Wifi,
                            title = stringResource(R.string.settings_screen_trusted_wifi_control_title),
                            subtitle = stringResource(
                                when {
                                    !trustedWifiEnabled -> R.string.settings_screen_trusted_wifi_desc_off
                                    !hasLocationAccess -> R.string.settings_screen_trusted_wifi_desc_needs_permission
                                    !locationEnabled -> R.string.settings_screen_trusted_wifi_desc_location_off
                                    trustedWifiSsids.isEmpty() -> R.string.settings_screen_trusted_wifi_desc_no_networks
                                    trustedNetworkState.trusted -> R.string.settings_screen_trusted_wifi_desc_trusted
                                    else -> R.string.settings_screen_trusted_wifi_desc_not_trusted
                                }
                            ),
                            checked = trustedWifiEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                when {
                                    !isChecked -> {
                                        appLockRepository.setTrustedWifiEnabled(false)
                                        trustedWifiEnabled = false
                                        TrustedNetworkMonitor.refresh(context)
                                    }

                                    !TrustedNetworkMonitor.hasLocationPermission(context) -> {
                                        locationPermissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.ACCESS_FINE_LOCATION,
                                                Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }

                                    !TrustedNetworkMonitor.hasBackgroundLocationPermission(context) -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            showBackgroundLocationDialog = true
                                        } else {
                                            backgroundLocationLauncher.launch(
                                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                            )
                                        }
                                    }

                                    else -> enableTrustedWifi()
                                }
                            }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Router,
                            title = stringResource(R.string.settings_screen_trusted_networks_title),
                            subtitle = if (trustedWifiEnabled)
                                context.resources.getQuantityString(
                                    R.plurals.settings_screen_trusted_networks_count,
                                    trustedWifiSsids.size,
                                    trustedWifiSsids.size
                                )
                            else
                                stringResource(R.string.settings_screen_trusted_networks_desc_disabled),
                            onClick = {
                                if (!trustedWifiEnabled) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_screen_trusted_networks_desc_disabled),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    showTrustedNetworksDialog = true
                                }
                            }
                        )
                    )
                )
            }

            item {
                SectionTitle(text = stringResource(R.string.settings_screen_automation_title))
            }

            item {
                SettingsGroup(
                    items = listOf(
                        ToggleSettingItem(
                            icon = Icons.Default.SettingsRemote,
                            title = stringResource(R.string.settings_screen_automation_control_title),
                            subtitle = stringResource(R.string.settings_screen_automation_control_desc),
                            checked = automationEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                if (isChecked && automationToken.isNullOrEmpty()) {
                                    automationToken = appLockRepository.regenerateAutomationToken()
                                }
                                appLockRepository.setAutomationEnabled(isChecked)
                                AutomationReceiver.setComponentEnabled(context, isChecked)
                                automationEnabled = isChecked
                            }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Key,
                            title = stringResource(R.string.settings_screen_automation_token_title),
                            subtitle = if (automationEnabled)
                                stringResource(R.string.settings_screen_automation_token_desc)
                            else
                                stringResource(R.string.settings_screen_automation_token_desc_disabled),
                            onClick = {
                                if (!automationEnabled) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_screen_automation_token_desc_disabled),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    if (automationToken.isNullOrEmpty()) {
                                        automationToken =
                                            appLockRepository.regenerateAutomationToken()
                                    }
                                    showAutomationDialog = true
                                }
                            }
                        )
                    )
                )
            }

            item {
                SectionTitle(text = stringResource(R.string.settings_screen_advanced_title))
            }

            item {
                SettingsGroup(
                    items = listOf(
                        ActionSettingItem(
                            icon = Icons.Outlined.Security,
                            title = stringResource(R.string.settings_Screen_export_audit),
                            subtitle = stringResource(R.string.settings_screen_export_audit_desc),
                            onClick = {
                                val uri = LogUtils.exportAuditLogs()
                                if (uri != null) {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share audit logs")
                                    )
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_screen_export_logs_error),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ),
                        ActionSettingItem(
                            icon = Icons.Outlined.BugReport,
                            title = stringResource(R.string.settings_screen_export_logs_title),
                            subtitle = stringResource(R.string.settings_screen_export_logs_desc),
                            onClick = {
                                val uri = LogUtils.exportLogs()
                                if (uri != null) {
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, "Share logs")
                                    )
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_screen_export_logs_error),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.Troubleshoot,
                            title = "Logging",
                            subtitle = "Enable debug logging for troubleshooting",
                            checked = loggingEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                loggingEnabled = isChecked
                                appLockRepository.setLoggingEnabled(isChecked)
                                LogUtils.setLoggingEnabled(isChecked)
                            }
                        )
                    )
                )
            }

            item {
                BackendSelectionCard(
                    appLockRepository = appLockRepository,
                    context = context,
                    shizukuPermissionLauncher = shizukuPermissionLauncher
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp, top = 4.dp)
    )
}

sealed class SettingItemType {
    data class Toggle(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val checked: Boolean,
        val enabled: Boolean,
        val onCheckedChange: (Boolean) -> Unit
    ): SettingItemType()

    data class Action(
        val icon: ImageVector,
        val title: String,
        val subtitle: String,
        val onClick: () -> Unit
    ): SettingItemType()
}

data class ToggleSettingItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val checked: Boolean,
    val enabled: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

data class ActionSettingItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
fun SettingsGroup(
    items: List<Any>
) {
    Column {
        items.forEachIndexed { index, item ->
            SettingsCard(index = index, listSize = items.size) {
                when (item) {
                    is ToggleSettingItem -> {
                        ToggleSettingRow(
                            icon = item.icon,
                            title = item.title,
                            subtitle = item.subtitle,
                            checked = item.checked,
                            enabled = item.enabled,
                            onCheckedChange = item.onCheckedChange
                        )
                    }

                    is ActionSettingItem -> {
                        ActionSettingRow(
                            icon = item.icon,
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = item.onClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    index: Int,
    listSize: Int,
    content: @Composable () -> Unit
) {
    val shape = when {
        listSize == 1 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 6.dp,
            bottomEnd = 6.dp
        )

        index == listSize - 1 -> RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 6.dp,
            bottomStart = 24.dp,
            bottomEnd = 24.dp
        )

        else -> RoundedCornerShape(6.dp)
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(
            initialScale = 0.95f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = shape
        ) {
            content()
        }
    }
}

@Composable
fun ToggleSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(enabled = enabled) { if (enabled) onCheckedChange(!checked) }
            .padding(vertical = 2.dp, horizontal = 4.dp),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        },
        trailingContent = {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

@Composable
fun ActionSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        },
        trailingContent = {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

@Composable
fun UnlockTimeDurationDialog(
    currentDuration: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val durations = listOf(0, 1, 5, 15, 30, 60, Integer.MAX_VALUE)
    var selectedDuration by remember { mutableIntStateOf(currentDuration) }

    if (!durations.contains(selectedDuration)) {
        selectedDuration = durations.minByOrNull { abs(it - currentDuration) } ?: 0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_unlock_duration_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_screen_unlock_duration_dialog_description_new))
                durations.forEach { duration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDuration = duration }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedDuration == duration,
                            onClick = { selectedDuration = duration }
                        )
                        Text(
                            text = when (duration) {
                                0 -> stringResource(R.string.settings_screen_unlock_duration_dialog_option_immediate)
                                1 -> stringResource(
                                    R.string.settings_screen_unlock_duration_dialog_option_minute,
                                    duration
                                )
                                60 -> stringResource(R.string.settings_screen_unlock_duration_dialog_option_hour)
                                Integer.MAX_VALUE -> "Until Screen Off"
                                else -> stringResource(
                                    R.string.settings_screen_unlock_duration_summary_minutes,
                                    duration
                                )
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedDuration) }) {
                Text(stringResource(R.string.confirm_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun BackendSelectionCard(
    appLockRepository: AppLockRepository,
    context: Context,
    shizukuPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    var selectedBackend by remember { mutableStateOf(appLockRepository.getBackendImplementation()) }

    Column {
        SectionTitle(text = stringResource(R.string.settings_screen_backend_implementation_title))

        Column {
            BackendImplementation.entries.forEachIndexed { index, backend ->
                SettingsCard(
                    index = index,
                    listSize = BackendImplementation.entries.size
                ) {
                    BackendSelectionItem(
                        backend = backend,
                        isSelected = selectedBackend == backend,
                        onClick = {
                            when (backend) {
                                BackendImplementation.SHIZUKU -> {
                                    if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_DENIED) {
                                        if (Shizuku.isPreV11()) {
                                            shizukuPermissionLauncher.launch(ShizukuProvider.PERMISSION)
                                        } else if (Shizuku.pingBinder()) {
                                            Shizuku.requestPermission(423)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.settings_screen_shizuku_not_running_toast),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        selectedBackend = backend
                                        appLockRepository.setBackendImplementation(
                                            BackendImplementation.SHIZUKU
                                        )
                                        context.startService(
                                            Intent(context, ShizukuAppLockService::class.java)
                                        )
                                    }
                                }
                                BackendImplementation.USAGE_STATS -> {
                                    if (!context.hasUsagePermission()) {
                                        val intent =
                                            Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.settings_screen_usage_permission_toast),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@BackendSelectionItem
                                    }
                                    selectedBackend = backend
                                    appLockRepository.setBackendImplementation(BackendImplementation.USAGE_STATS)
                                    context.startService(
                                        Intent(context, UsageLockService::class.java)
                                    )
                                }
                                BackendImplementation.ACCESSIBILITY -> {
                                    if (!context.isAccessibilityServiceEnabled()) {
                                        openAccessibilitySettings(context)
                                        return@BackendSelectionItem
                                    }
                                    selectedBackend = backend
                                    appLockRepository.setBackendImplementation(BackendImplementation.ACCESSIBILITY)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackendSelectionItem(
    backend: BackendImplementation,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable { onClick() }
            .padding(vertical = 2.dp, horizontal = 4.dp),
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = getBackendDisplayName(backend),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                if (backend == BackendImplementation.SHIZUKU) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ) {
                        Text(
                            text = stringResource(R.string.settings_screen_backend_implementation_shizuku_advanced),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        supportingContent = {
            Text(
                text = getBackendDescription(backend),
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getBackendIcon(backend),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        },
        trailingContent = {
            Box(
                contentAlignment = Alignment.Center
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

private fun getBackendDisplayName(backend: BackendImplementation): String {
    return when (backend) {
        BackendImplementation.ACCESSIBILITY -> "Accessibility Service"
        BackendImplementation.USAGE_STATS -> "Usage Statistics"
        BackendImplementation.SHIZUKU -> "Shizuku Service"
    }
}

private fun getBackendDescription(backend: BackendImplementation): String {
    return when (backend) {
        BackendImplementation.ACCESSIBILITY -> "Standard method that works on most devices"
        BackendImplementation.USAGE_STATS -> "Experimental method using app usage statistics"
        BackendImplementation.SHIZUKU -> "Advanced method using Shizuku and internal APIs"
    }
}

private fun getBackendIcon(backend: BackendImplementation): ImageVector {
    return when (backend) {
        BackendImplementation.ACCESSIBILITY -> Accessibility
        BackendImplementation.USAGE_STATS -> Icons.Default.QueryStats
        BackendImplementation.SHIZUKU -> Icons.Default.AutoAwesome
    }
}

@Composable
fun PermissionRequiredDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_permission_required_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_screen_permission_required_dialog_text_1))
                Text(stringResource(R.string.settings_screen_permission_required_dialog_text_2))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.grant_permission_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun DeviceAdminDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_device_admin_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_screen_device_admin_dialog_text_1))
                Text(stringResource(R.string.settings_screen_device_admin_dialog_text_2))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.enable_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun AccessibilityDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_accessibility_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_screen_accessibility_dialog_text_1))
                Text(stringResource(R.string.settings_screen_accessibility_dialog_text_2))
                Text(stringResource(R.string.settings_screen_accessibility_dialog_text_3))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.enable_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        }
    )
}

@Composable
fun AutomationTokenDialog(
    token: String,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_automation_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_screen_automation_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                MonospaceBlock(
                    listOf(
                        AutomationReceiver.ACTION_ENABLE_PROTECTION,
                        AutomationReceiver.ACTION_DISABLE_PROTECTION,
                        AutomationReceiver.ACTION_QUERY_STATE
                    ).joinToString("\n")
                )
                Text(
                    text = stringResource(R.string.settings_screen_automation_dialog_token_label),
                    style = MaterialTheme.typography.labelLarge
                )
                MonospaceBlock(token)
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onCopy) {
                Text(stringResource(R.string.settings_screen_automation_copy))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRegenerate) {
                    Text(stringResource(R.string.settings_screen_automation_regenerate))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_screen_automation_close))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun TrustedNetworksDialog(
    trustedSsids: Set<String>,
    currentSsid: String?,
    locationEnabled: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpenLocationSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val addableSsid = currentSsid?.takeIf { it !in trustedSsids }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_trusted_networks_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_screen_trusted_networks_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium
                )

                if (trustedSsids.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_screen_trusted_networks_dialog_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                trustedSsids.sortedBy { TrustedNetworkMonitor.displayName(it).lowercase() }
                    .forEach { ssid ->
                        val name = TrustedNetworkMonitor.displayName(ssid)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp)
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onRemove(ssid) }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(
                                            R.string.settings_screen_trusted_networks_remove_cd,
                                            name
                                        )
                                    )
                                }
                            }
                        }
                    }

                val hint = when {
                    !locationEnabled -> R.string.settings_screen_trusted_networks_dialog_location_off
                    currentSsid == null -> R.string.settings_screen_trusted_networks_dialog_not_connected
                    else -> null
                }
                if (hint != null) {
                    Text(
                        text = stringResource(hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (!locationEnabled) {
                FilledTonalButton(onClick = onOpenLocationSettings) {
                    Text(stringResource(R.string.settings_screen_trusted_networks_turn_on_location))
                }
            } else if (addableSsid != null) {
                FilledTonalButton(onClick = { onAdd(addableSsid) }) {
                    Text(
                        stringResource(
                            R.string.settings_screen_trusted_networks_add,
                            TrustedNetworkMonitor.displayName(addableSsid)
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_screen_trusted_networks_close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun MonospaceBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp)
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Copies the token, flagged sensitive so Android 13+ keeps it out of the clipboard preview.
 */
private fun copyTokenToClipboard(context: Context, token: String) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = ClipData.newPlainText("AppLock automation token", token)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
