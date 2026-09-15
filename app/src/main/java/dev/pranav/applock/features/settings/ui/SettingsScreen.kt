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
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import dev.pranav.applock.R
import dev.pranav.applock.core.broadcast.AutomationReceiver
import dev.pranav.applock.core.broadcast.DeviceAdmin
import dev.pranav.applock.core.intruder.IntruderAlerts
import dev.pranav.applock.core.intruder.IntruderCapture
import dev.pranav.applock.core.intruder.IntruderLocation
import dev.pranav.applock.core.intruder.IntruderSendJob
import dev.pranav.applock.core.navigation.Screen
import dev.pranav.applock.core.network.LockScreenContentByNetwork
import dev.pranav.applock.core.network.ScreenTimeoutByNetwork
import dev.pranav.applock.core.network.TrustedNetworkMonitor
import dev.pranav.applock.core.remotelock.RemoteLock
import dev.pranav.applock.core.remotelock.RemoteLockListener
import dev.pranav.applock.core.remotelock.RemoteLockSmsReceiver
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.PhoneLocker
import dev.pranav.applock.core.utils.canAuthenticateBiometrics
import dev.pranav.applock.core.utils.hasUsagePermission
import dev.pranav.applock.core.utils.isAccessibilityServiceEnabled
import dev.pranav.applock.core.utils.openAccessibilitySettings
import dev.pranav.applock.data.repository.AppLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.data.repository.IntruderCaptureMode
import dev.pranav.applock.data.repository.PreferencesRepository
import dev.pranav.applock.features.admin.AdminDisableActivity
import dev.pranav.applock.features.antiuninstall.ui.ShizukuState
import dev.pranav.applock.features.antiuninstall.ui.checkShizukuState
import dev.pranav.applock.services.AppLockAccessibilityService
import dev.pranav.applock.services.ShizukuAppLockService
import dev.pranav.applock.services.UsageLockService
import dev.pranav.applock.ui.icons.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.math.abs

/** The trusted Wi-Fi options that ask for location access, so the grant turns on the one that asked. */
private enum class TrustedWifiOption { OPEN_APPS, SCREEN_TIMEOUT, LOCK_SCREEN_CONTENT }

/** Shizuku's own permission, when it's asked for in order to grant secure settings. */
private const val SHIZUKU_GRANT_REQUEST_CODE = 424

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
    var screenTimeoutEnabled by remember { mutableStateOf(appLockRepository.isScreenTimeoutByNetworkEnabled()) }
    var screenTimeoutTrustedSeconds by remember { mutableIntStateOf(appLockRepository.getScreenTimeoutTrustedSeconds()) }
    var screenTimeoutAwaySeconds by remember { mutableIntStateOf(appLockRepository.getScreenTimeoutAwaySeconds()) }
    var canWriteSettings by remember { mutableStateOf(ScreenTimeoutByNetwork.canWrite(context)) }
    var showWriteSettingsDialog by remember { mutableStateOf(false) }
    var showScreenTimeoutTrustedDialog by remember { mutableStateOf(false) }
    var showScreenTimeoutAwayDialog by remember { mutableStateOf(false) }
    var pendingEnableScreenTimeout by remember { mutableStateOf(false) }
    var lockScreenContentEnabled by remember { mutableStateOf(appLockRepository.isLockScreenContentByNetworkEnabled()) }
    var canWriteSecureSettings by remember { mutableStateOf(LockScreenContentByNetwork.canWrite(context)) }
    var showSecureSettingsDialog by remember { mutableStateOf(false) }
    // Set while Shizuku asks for its own permission, so its answer carries on the grant.
    var pendingShizukuGrant by remember { mutableStateOf(false) }
    // Which trusted Wi-Fi option asked for location access, so the grant turns that one on.
    var locationRequestFor by remember { mutableStateOf(TrustedWifiOption.OPEN_APPS) }

    var intruderAlertsEnabled by remember { mutableStateOf(appLockRepository.isIntruderAlertsEnabled()) }
    var intruderCaptureMode by remember { mutableStateOf(appLockRepository.getIntruderCaptureMode()) }
    var intruderThreshold by remember { mutableIntStateOf(appLockRepository.getIntruderThreshold()) }
    var intruderLocation by remember { mutableStateOf(appLockRepository.isIntruderLocationEnabled()) }
    var intruderEmailConfigured by remember { mutableStateOf(appLockRepository.isIntruderEmailConfigured()) }
    var intruderSendError by remember { mutableStateOf(appLockRepository.getIntruderSendError()) }
    var showIntruderEmailDialog by remember { mutableStateOf(false) }
    var showIntruderCaptureDialog by remember { mutableStateOf(false) }
    var showIntruderThresholdDialog by remember { mutableStateOf(false) }
    var pendingEnableIntruder by remember { mutableStateOf(false) }

    var remoteLockEnabled by remember { mutableStateOf(appLockRepository.isRemoteLockEnabled()) }
    var remoteLockKeyword by remember { mutableStateOf(appLockRepository.getRemoteLockKeyword()) }
    var remoteLockPhone by remember { mutableStateOf(appLockRepository.isRemoteLockPhoneEnabled()) }
    var remoteLockEmail by remember { mutableStateOf(appLockRepository.isRemoteLockEmailEnabled()) }
    var hasSmsAccess by remember { mutableStateOf(RemoteLockSmsReceiver.hasPermission(context)) }
    var hasNotificationAccess by remember { mutableStateOf(RemoteLockListener.hasAccess(context)) }
    var phoneLockAvailable by remember { mutableStateOf(canLockPhone(context)) }
    var showRemoteLockKeywordDialog by remember { mutableStateOf(false) }
    var pendingEnableRemoteLock by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

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
                // The secure settings grant can also be made or taken away from a computer.
                canWriteSecureSettings = LockScreenContentByNetwork.canWrite(context)
                if (appLockRepository.usesTrustedNetworks()) TrustedNetworkMonitor.refresh(context)
                // An alert can fail while this screen is away, so pick the reason up on return.
                intruderSendError = appLockRepository.getIntruderSendError()
                // SMS and notification access are granted in Android's settings, and the ways to
                // lock the phone can change there too.
                hasSmsAccess = RemoteLockSmsReceiver.hasPermission(context)
                hasNotificationAccess = RemoteLockListener.hasAccess(context)
                phoneLockAvailable = canLockPhone(context)
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

    fun enableScreenTimeout() {
        appLockRepository.setScreenTimeoutByNetworkEnabled(true)
        screenTimeoutEnabled = true
        hasLocationAccess = true
        // Also writes the timeout for the network the phone is on now.
        TrustedNetworkMonitor.refresh(context)
        if (trustedWifiSsids.isEmpty()) showTrustedNetworksDialog = true
    }

    fun disableScreenTimeout() {
        appLockRepository.setScreenTimeoutByNetworkEnabled(false)
        screenTimeoutEnabled = false
        // Android keeps whatever was written last, so leave the shorter timeout behind.
        ScreenTimeoutByNetwork.applyAway(context)
        TrustedNetworkMonitor.refresh(context)
    }

    fun enableLockScreenContent() {
        appLockRepository.setLockScreenContentByNetworkEnabled(true)
        lockScreenContentEnabled = true
        hasLocationAccess = true
        // Also shows or hides content for the network the phone is on now.
        TrustedNetworkMonitor.refresh(context)
        if (trustedWifiSsids.isEmpty()) showTrustedNetworksDialog = true
    }

    fun disableLockScreenContent() {
        appLockRepository.setLockScreenContentByNetworkEnabled(false)
        lockScreenContentEnabled = false
        // Android keeps whatever was written last, so leave content hidden.
        LockScreenContentByNetwork.applyHidden(context)
        TrustedNetworkMonitor.refresh(context)
    }

    // Turns on whichever trusted Wi-Fi option asked for location access. The screen timeout and
    // notification content check their own permission before they ask.
    fun onLocationAccessGranted() {
        when (locationRequestFor) {
            TrustedWifiOption.OPEN_APPS -> enableTrustedWifi()
            TrustedWifiOption.SCREEN_TIMEOUT -> enableScreenTimeout()
            TrustedWifiOption.LOCK_SCREEN_CONTENT -> enableLockScreenContent()
        }
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onLocationAccessGranted()
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

            TrustedNetworkMonitor.hasBackgroundLocationPermission(context) -> onLocationAccessGranted()

            // From Android 11, "Allow all the time" is only offered on its own settings page.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                showBackgroundLocationDialog = true
            }

            else -> backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // Asks for whichever location access is still missing, for one of the trusted Wi-Fi options.
    fun requestLocationAccess(option: TrustedWifiOption) {
        locationRequestFor = option
        when {
            !TrustedNetworkMonitor.hasLocationPermission(context) -> {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> showBackgroundLocationDialog = true

            else -> backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    // Turns the screen timeout option on once the phone's settings can be written and the network
    // name can be read, asking for whichever is still missing.
    fun tryEnableScreenTimeout() {
        when {
            !ScreenTimeoutByNetwork.canWrite(context) -> showWriteSettingsDialog = true

            !TrustedNetworkMonitor.hasLocationPermission(context) ||
                    !TrustedNetworkMonitor.hasBackgroundLocationPermission(context) ->
                requestLocationAccess(TrustedWifiOption.SCREEN_TIMEOUT)

            else -> enableScreenTimeout()
        }
    }

    // Turns notification content by network on once secure settings can be written and the network
    // name can be read, asking for whichever is still missing.
    fun tryEnableLockScreenContent() {
        when {
            !LockScreenContentByNetwork.canWrite(context) -> showSecureSettingsDialog = true

            !TrustedNetworkMonitor.hasLocationPermission(context) ||
                    !TrustedNetworkMonitor.hasBackgroundLocationPermission(context) ->
                requestLocationAccess(TrustedWifiOption.LOCK_SCREEN_CONTENT)

            else -> enableLockScreenContent()
        }
    }

    // However the permission was granted, close the grant dialog and carry on turning the option on.
    fun onSecureSettingsGranted() {
        canWriteSecureSettings = true
        showSecureSettingsDialog = false
        tryEnableLockScreenContent()
    }

    fun grantSecureSettingsWithShizuku() {
        coroutineScope.launch {
            val granted = withContext(Dispatchers.IO) {
                LockScreenContentByNetwork.grantWithShizuku(context)
            }
            if (granted) {
                onSecureSettingsGranted()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_lock_screen_content_shizuku_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Runs the grant when Shizuku is ready. When this app isn't allowed in Shizuku yet, Shizuku asks
    // first, and the listener below carries on once the user answers.
    fun onGrantWithShizukuClick() {
        when (checkShizukuState(context)) {
            ShizukuState.READY -> grantSecureSettingsWithShizuku()

            ShizukuState.PERMISSION_DENIED -> {
                pendingShizukuGrant = true
                Shizuku.requestPermission(SHIZUKU_GRANT_REQUEST_CODE)
            }

            else -> Toast.makeText(
                context,
                context.getString(R.string.settings_screen_shizuku_not_running_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // "Modify system settings" is granted on Android's own page, so carry on turning the screen
    // timeout on when the user comes back from it.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            canWriteSettings = ScreenTimeoutByNetwork.canWrite(context)
            if (!pendingEnableScreenTimeout) return@LifecycleEventObserver

            pendingEnableScreenTimeout = false
            if (canWriteSettings) {
                tryEnableScreenTimeout()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_screen_timeout_write_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Shizuku asks for its own permission in a dialog of its own, so carry on the grant once the
    // user answers there.
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_GRANT_REQUEST_CODE && pendingShizukuGrant) {
                pendingShizukuGrant = false
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    grantSecureSettingsWithShizuku()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_screen_shizuku_permission_required_desc),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    val intruderLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (IntruderLocation.hasPermission(context)) {
            intruderLocation = true
            appLockRepository.setIntruderLocationEnabled(true)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.settings_screen_intruder_location_denied),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val intruderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (IntruderCapture.hasCameraPermission(context)) {
            appLockRepository.setIntruderAlertsEnabled(true)
            intruderAlertsEnabled = true
            if (IntruderCapture.recordsVideo(intruderCaptureMode) &&
                !IntruderCapture.hasMicrophonePermission(context)
            ) {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_intruder_enabled_no_sound),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.settings_screen_intruder_camera_needed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Enables intruder alerts once the email is set up and the camera is granted, asking for
    // whichever is still missing. The email dialog and the permission result call it again.
    fun tryEnableIntruderAlerts() {
        when {
            !appLockRepository.isIntruderEmailConfigured() -> {
                pendingEnableIntruder = true
                showIntruderEmailDialog = true
            }

            !IntruderCapture.hasCameraPermission(context) -> {
                val permissions = if (IntruderCapture.recordsVideo(intruderCaptureMode)) {
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                } else {
                    arrayOf(Manifest.permission.CAMERA)
                }
                intruderPermissionLauncher.launch(permissions)
            }

            else -> {
                appLockRepository.setIntruderAlertsEnabled(true)
                intruderAlertsEnabled = true
            }
        }
    }

    // The SMS receiver is only part of the app while remote lock is on, like the automation receiver.
    fun switchRemoteLock(enabled: Boolean) {
        appLockRepository.setRemoteLockEnabled(enabled)
        RemoteLockSmsReceiver.setComponentEnabled(context, enabled)
        remoteLockEnabled = enabled
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasSmsAccess = isGranted
        if (!isGranted) {
            // On Android 15+ a sideloaded app is refused without a prompt until restricted settings
            // are allowed for it, so say where that is.
            Toast.makeText(
                context,
                context.getString(R.string.settings_screen_remote_lock_sms_denied),
                Toast.LENGTH_LONG
            ).show()
        }
        if (pendingEnableRemoteLock) {
            pendingEnableRemoteLock = false
            if (isGranted) switchRemoteLock(true)
        }
    }

    // Turns remote lock on once there is a keyword and a way for a message to arrive, asking for
    // whichever is missing. Notification access is granted in Android's settings, from its own row.
    fun tryEnableRemoteLock() {
        when {
            appLockRepository.getRemoteLockKeyword() == null -> {
                pendingEnableRemoteLock = true
                showRemoteLockKeywordDialog = true
            }

            !RemoteLockSmsReceiver.hasPermission(context) &&
                    !RemoteLockListener.hasAccess(context) -> {
                pendingEnableRemoteLock = true
                smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
            }

            else -> switchRemoteLock(true)
        }
    }

    if (showRemoteLockKeywordDialog) {
        RemoteLockKeywordDialog(
            keyword = remoteLockKeyword.orEmpty(),
            onSave = { keyword ->
                appLockRepository.setRemoteLockKeyword(keyword)
                remoteLockKeyword = appLockRepository.getRemoteLockKeyword()
                showRemoteLockKeywordDialog = false
                if (pendingEnableRemoteLock) {
                    pendingEnableRemoteLock = false
                    tryEnableRemoteLock()
                }
            },
            onDismiss = {
                showRemoteLockKeywordDialog = false
                pendingEnableRemoteLock = false
            }
        )
    }

    if (showIntruderEmailDialog) {
        IntruderEmailDialog(
            hasStoredKey = !appLockRepository.getIntruderApiKey().isNullOrBlank(),
            from = appLockRepository.getIntruderEmailFrom(),
            to = appLockRepository.getIntruderEmailTo(),
            onSave = { apiKey, from, to ->
                appLockRepository.setIntruderEmail(apiKey, from, to)
                intruderEmailConfigured = appLockRepository.isIntruderEmailConfigured()
                intruderSendError = appLockRepository.getIntruderSendError()
                showIntruderEmailDialog = false
                // Alerts captured before the email worked are still waiting: give them another try
                // now rather than leaving them until the next alert.
                if (intruderEmailConfigured) IntruderSendJob.schedule(context)
                if (pendingEnableIntruder) {
                    pendingEnableIntruder = false
                    tryEnableIntruderAlerts()
                }
            },
            onDismiss = {
                showIntruderEmailDialog = false
                pendingEnableIntruder = false
            }
        )
    }

    if (showIntruderCaptureDialog) {
        IntruderCaptureModeDialog(
            selected = intruderCaptureMode,
            onSelect = { mode ->
                intruderCaptureMode = mode
                appLockRepository.setIntruderCaptureMode(mode)
                showIntruderCaptureDialog = false
                // Switching to video while it's already on: ask for the mic so sound works.
                if (IntruderCapture.recordsVideo(mode) && intruderAlertsEnabled &&
                    !IntruderCapture.hasMicrophonePermission(context)
                ) {
                    intruderPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            },
            onDismiss = { showIntruderCaptureDialog = false }
        )
    }

    if (showIntruderThresholdDialog) {
        IntruderThresholdDialog(
            selected = intruderThreshold,
            onSelect = { tries ->
                intruderThreshold = tries
                appLockRepository.setIntruderThreshold(tries)
                showIntruderThresholdDialog = false
            },
            onDismiss = { showIntruderThresholdDialog = false }
        )
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

    if (showWriteSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showWriteSettingsDialog = false },
            title = { Text(stringResource(R.string.settings_screen_screen_timeout_write_dialog_title)) },
            text = { Text(stringResource(R.string.settings_screen_screen_timeout_write_dialog_text)) },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showWriteSettingsDialog = false
                        pendingEnableScreenTimeout = true
                        ScreenTimeoutByNetwork.openPermissionPage(context)
                    }
                ) {
                    Text(stringResource(R.string.settings_screen_trusted_wifi_background_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWriteSettingsDialog = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showSecureSettingsDialog) {
        SecureSettingsGrantDialog(
            adbCommand = LockScreenContentByNetwork.adbGrantCommand(context),
            onGrantWithShizuku = { onGrantWithShizukuClick() },
            onCopyCommand = {
                copyCommandToClipboard(context, LockScreenContentByNetwork.adbGrantCommand(context))
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_screen_lock_screen_content_command_copied),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onCheckAgain = {
                if (LockScreenContentByNetwork.canWrite(context)) {
                    onSecureSettingsGranted()
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_screen_lock_screen_content_not_granted),
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDismiss = {
                showSecureSettingsDialog = false
                pendingShizukuGrant = false
            }
        )
    }

    if (showScreenTimeoutTrustedDialog) {
        ScreenTimeoutDialog(
            title = stringResource(R.string.settings_screen_screen_timeout_trusted_dialog_title),
            selected = screenTimeoutTrustedSeconds,
            onSelect = { seconds ->
                appLockRepository.setScreenTimeoutTrustedSeconds(seconds)
                screenTimeoutTrustedSeconds = seconds
                showScreenTimeoutTrustedDialog = false
                // Writes the new value at once if it's the one in use.
                if (screenTimeoutEnabled) TrustedNetworkMonitor.refresh(context)
            },
            onDismiss = { showScreenTimeoutTrustedDialog = false }
        )
    }

    if (showScreenTimeoutAwayDialog) {
        ScreenTimeoutDialog(
            title = stringResource(R.string.settings_screen_screen_timeout_away_dialog_title),
            selected = screenTimeoutAwaySeconds,
            onSelect = { seconds ->
                appLockRepository.setScreenTimeoutAwaySeconds(seconds)
                screenTimeoutAwaySeconds = seconds
                showScreenTimeoutAwayDialog = false
                if (screenTimeoutEnabled) TrustedNetworkMonitor.refresh(context)
            },
            onDismiss = { showScreenTimeoutAwayDialog = false }
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
                val versionName = packageInfo?.versionName
                    ?: stringResource(R.string.settings_screen_version_unknown)
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
                                if (unlockTimeDuration > 10_000) stringResource(R.string.settings_screen_unlock_duration_summary_until_screen_off)
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
                SectionTitle(text = stringResource(R.string.settings_screen_intruder_title))
            }

            item {
                SettingsGroup(
                    items = listOfNotNull(
                        ToggleSettingItem(
                            icon = Icons.Default.PhotoCamera,
                            title = stringResource(R.string.settings_screen_intruder_control_title),
                            subtitle = when {
                                intruderAlertsEnabled -> stringResource(
                                    R.string.settings_screen_intruder_desc_on,
                                    intruderThreshold
                                )
                                !intruderEmailConfigured ->
                                    stringResource(R.string.settings_screen_intruder_desc_needs_email)
                                else -> stringResource(R.string.settings_screen_intruder_desc_off)
                            },
                            checked = intruderAlertsEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    tryEnableIntruderAlerts()
                                } else {
                                    appLockRepository.setIntruderAlertsEnabled(false)
                                    intruderAlertsEnabled = false
                                }
                            }
                        ),
                        ActionSettingItem(
                            icon = if (IntruderCapture.recordsVideo(intruderCaptureMode))
                                Icons.Default.Videocam else Icons.Default.PhotoCamera,
                            title = stringResource(R.string.settings_screen_intruder_capture_title),
                            subtitle = stringResource(captureModeLabel(intruderCaptureMode)),
                            onClick = { showIntruderCaptureDialog = true }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Numbers,
                            title = stringResource(R.string.settings_screen_intruder_threshold_title),
                            subtitle = stringResource(
                                R.string.settings_screen_intruder_threshold_desc,
                                intruderThreshold
                            ),
                            onClick = { showIntruderThresholdDialog = true }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.LocationOn,
                            title = stringResource(R.string.settings_screen_intruder_location_title),
                            subtitle = stringResource(R.string.settings_screen_intruder_location_desc),
                            checked = intruderLocation,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                if (isChecked && !IntruderLocation.hasPermission(context)) {
                                    intruderLocationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                } else {
                                    intruderLocation = isChecked
                                    appLockRepository.setIntruderLocationEnabled(isChecked)
                                }
                            }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Email,
                            title = stringResource(R.string.settings_screen_intruder_email_title),
                            // A refusal Resend gives back, such as a recipient that needs a verified
                            // domain, would otherwise only show up in the test alert.
                            subtitle = when {
                                intruderSendError != null -> stringResource(
                                    R.string.settings_screen_intruder_email_desc_error,
                                    intruderSendError.orEmpty()
                                )

                                intruderEmailConfigured -> stringResource(
                                    R.string.settings_screen_intruder_email_desc_set,
                                    appLockRepository.getIntruderEmailTo()
                                )

                                else -> stringResource(R.string.settings_screen_intruder_email_desc_unset)
                            },
                            onClick = { showIntruderEmailDialog = true }
                        ),
                        if (intruderEmailConfigured) ActionSettingItem(
                            icon = Icons.AutoMirrored.Filled.Send,
                            title = stringResource(R.string.settings_screen_intruder_test_title),
                            subtitle = stringResource(R.string.settings_screen_intruder_test_desc),
                            onClick = {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_screen_intruder_test_running),
                                    Toast.LENGTH_SHORT
                                ).show()
                                coroutineScope.launch {
                                    val message = IntruderAlerts.sendTest(context)
                                    intruderSendError = appLockRepository.getIntruderSendError()
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        ) else null
                    )
                )
            }

            item {
                SectionTitle(text = stringResource(R.string.settings_screen_remote_lock_title))
            }

            item {
                SettingsGroup(
                    items = listOf(
                        ToggleSettingItem(
                            icon = Icons.Default.PhonelinkLock,
                            title = stringResource(R.string.settings_screen_remote_lock_control_title),
                            subtitle = when {
                                !remoteLockEnabled ->
                                    stringResource(R.string.settings_screen_remote_lock_desc_off)

                                !hasSmsAccess && !hasNotificationAccess ->
                                    stringResource(R.string.settings_screen_remote_lock_desc_no_access)

                                else -> stringResource(
                                    R.string.settings_screen_remote_lock_desc_on,
                                    remoteLockKeyword.orEmpty()
                                )
                            },
                            checked = remoteLockEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                if (isChecked) tryEnableRemoteLock() else switchRemoteLock(false)
                            }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Key,
                            title = stringResource(R.string.settings_screen_remote_lock_keyword_title),
                            subtitle = remoteLockKeyword?.let {
                                stringResource(R.string.settings_screen_remote_lock_keyword_desc_set, it)
                            } ?: stringResource(R.string.settings_screen_remote_lock_keyword_desc_unset),
                            onClick = { showRemoteLockKeywordDialog = true }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.NotificationsActive,
                            title = stringResource(R.string.settings_screen_remote_lock_notifications_title),
                            subtitle = stringResource(
                                if (hasNotificationAccess) {
                                    R.string.settings_screen_remote_lock_notifications_desc_allowed
                                } else {
                                    R.string.settings_screen_remote_lock_notifications_desc_denied
                                }
                            ),
                            onClick = { openNotificationAccessSettings(context) }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Sms,
                            title = stringResource(R.string.settings_screen_remote_lock_sms_title),
                            subtitle = stringResource(
                                if (hasSmsAccess) {
                                    R.string.settings_screen_remote_lock_sms_desc_allowed
                                } else {
                                    R.string.settings_screen_remote_lock_sms_desc_denied
                                }
                            ),
                            // Once allowed, SMS is taken back in Android's App info, which
                            // anti-uninstall bounces, so there is nothing to open from here.
                            onClick = {
                                if (!hasSmsAccess) {
                                    smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                                }
                            }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.ScreenLockPortrait,
                            title = stringResource(R.string.settings_screen_remote_lock_phone_title),
                            subtitle = stringResource(
                                if (phoneLockAvailable) {
                                    R.string.settings_screen_remote_lock_phone_desc
                                } else {
                                    R.string.settings_screen_remote_lock_phone_desc_unavailable
                                }
                            ),
                            checked = remoteLockPhone,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                remoteLockPhone = isChecked
                                appLockRepository.setRemoteLockPhoneEnabled(isChecked)
                            }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.Email,
                            title = stringResource(R.string.settings_screen_remote_lock_email_title),
                            subtitle = stringResource(
                                if (intruderEmailConfigured) {
                                    R.string.settings_screen_remote_lock_email_desc
                                } else {
                                    R.string.settings_screen_remote_lock_email_desc_unset
                                }
                            ),
                            checked = remoteLockEmail && intruderEmailConfigured,
                            enabled = intruderEmailConfigured,
                            onCheckedChange = { isChecked ->
                                remoteLockEmail = isChecked
                                appLockRepository.setRemoteLockEmailEnabled(isChecked)
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

                                    !TrustedNetworkMonitor.hasLocationPermission(context) ||
                                            !TrustedNetworkMonitor.hasBackgroundLocationPermission(context) ->
                                        requestLocationAccess(TrustedWifiOption.OPEN_APPS)

                                    else -> enableTrustedWifi()
                                }
                            }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.AvTimer,
                            title = stringResource(R.string.settings_screen_screen_timeout_control_title),
                            subtitle = when {
                                !screenTimeoutEnabled ->
                                    stringResource(R.string.settings_screen_screen_timeout_desc_off)

                                !canWriteSettings ->
                                    stringResource(R.string.settings_screen_screen_timeout_desc_needs_write)

                                !hasLocationAccess ->
                                    stringResource(R.string.settings_screen_screen_timeout_desc_needs_location)

                                !locationEnabled ->
                                    stringResource(R.string.settings_screen_screen_timeout_desc_location_off)

                                trustedWifiSsids.isEmpty() ->
                                    stringResource(R.string.settings_screen_trusted_wifi_desc_no_networks)

                                trustedNetworkState.trusted -> stringResource(
                                    R.string.settings_screen_screen_timeout_desc_trusted,
                                    screenTimeoutLabel(context, screenTimeoutTrustedSeconds)
                                )

                                else -> stringResource(
                                    R.string.settings_screen_screen_timeout_desc_not_trusted,
                                    screenTimeoutLabel(context, screenTimeoutAwaySeconds)
                                )
                            },
                            checked = screenTimeoutEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                if (isChecked) tryEnableScreenTimeout() else disableScreenTimeout()
                            }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Home,
                            title = stringResource(R.string.settings_screen_screen_timeout_trusted_title),
                            subtitle = screenTimeoutLabel(context, screenTimeoutTrustedSeconds),
                            onClick = { showScreenTimeoutTrustedDialog = true }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.WifiOff,
                            title = stringResource(R.string.settings_screen_screen_timeout_away_title),
                            subtitle = screenTimeoutLabel(context, screenTimeoutAwaySeconds),
                            onClick = { showScreenTimeoutAwayDialog = true }
                        ),
                        ToggleSettingItem(
                            icon = Icons.Default.Notifications,
                            title = stringResource(R.string.settings_screen_lock_screen_content_control_title),
                            subtitle = stringResource(
                                when {
                                    !lockScreenContentEnabled -> R.string.settings_screen_lock_screen_content_desc_off
                                    !canWriteSecureSettings -> R.string.settings_screen_lock_screen_content_desc_needs_grant
                                    !hasLocationAccess -> R.string.settings_screen_lock_screen_content_desc_needs_location
                                    !locationEnabled -> R.string.settings_screen_lock_screen_content_desc_location_off
                                    trustedWifiSsids.isEmpty() -> R.string.settings_screen_trusted_wifi_desc_no_networks
                                    trustedNetworkState.trusted -> R.string.settings_screen_lock_screen_content_desc_trusted
                                    else -> R.string.settings_screen_lock_screen_content_desc_not_trusted
                                }
                            ),
                            checked = lockScreenContentEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                if (isChecked) tryEnableLockScreenContent() else disableLockScreenContent()
                            }
                        ),
                        ActionSettingItem(
                            icon = Icons.Default.Router,
                            title = stringResource(R.string.settings_screen_trusted_networks_title),
                            subtitle = if (trustedWifiEnabled || screenTimeoutEnabled || lockScreenContentEnabled)
                                context.resources.getQuantityString(
                                    R.plurals.settings_screen_trusted_networks_count,
                                    trustedWifiSsids.size,
                                    trustedWifiSsids.size
                                )
                            else
                                stringResource(R.string.settings_screen_trusted_networks_desc_disabled),
                            onClick = {
                                if (!trustedWifiEnabled && !screenTimeoutEnabled && !lockScreenContentEnabled) {
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
                    items = listOfNotNull(
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
                                        Intent.createChooser(
                                            shareIntent,
                                            context.getString(R.string.settings_screen_share_logs_chooser)
                                        )
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
                            title = stringResource(R.string.settings_screen_logging_title),
                            subtitle = stringResource(R.string.settings_screen_logging_desc),
                            checked = loggingEnabled,
                            enabled = true,
                            onCheckedChange = { isChecked ->
                                loggingEnabled = isChecked
                                appLockRepository.setLoggingEnabled(isChecked)
                                LogUtils.setLoggingEnabled(isChecked)
                            }
                        ),
                        // Troubleshooting only, so it comes and goes with the logging switch above.
                        // Worth having at all because the lock it checks is otherwise silent: it
                        // runs only while the accessibility service is going away, so nothing else
                        // would show that it had stopped working.
                        if (loggingEnabled) ActionSettingItem(
                            icon = Icons.Default.Lock,
                            title = stringResource(R.string.settings_screen_test_lock_title),
                            subtitle = stringResource(R.string.settings_screen_test_lock_desc),
                            onClick = {
                                if (!PhoneLocker.lockWithDeviceAdmin(context, "test from settings")) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_screen_test_lock_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        ) else null
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
                                Integer.MAX_VALUE -> stringResource(R.string.settings_screen_unlock_duration_dialog_option_until_screen_off)
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
                    text = stringResource(getBackendDisplayName(backend)),
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
                text = stringResource(getBackendDescription(backend)),
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

@StringRes
private fun getBackendDisplayName(backend: BackendImplementation): Int {
    return when (backend) {
        BackendImplementation.ACCESSIBILITY -> R.string.accessibility_service_title
        BackendImplementation.USAGE_STATS -> R.string.settings_screen_backend_usage_stats_name
        BackendImplementation.SHIZUKU -> R.string.shizuku_service_title
    }
}

@StringRes
private fun getBackendDescription(backend: BackendImplementation): Int {
    return when (backend) {
        BackendImplementation.ACCESSIBILITY -> R.string.settings_screen_backend_accessibility_summary
        BackendImplementation.USAGE_STATS -> R.string.settings_screen_backend_usage_stats_summary
        BackendImplementation.SHIZUKU -> R.string.settings_screen_backend_shizuku_summary
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

/** The Settings label for each capture mode. */
private fun captureModeLabel(mode: IntruderCaptureMode): Int = when (mode) {
    IntruderCaptureMode.PHOTO -> R.string.settings_screen_intruder_capture_photo
    IntruderCaptureMode.VIDEO -> R.string.settings_screen_intruder_capture_video
    IntruderCaptureMode.BOTH -> R.string.settings_screen_intruder_capture_both
}

@Composable
fun IntruderCaptureModeDialog(
    selected: IntruderCaptureMode,
    onSelect: (IntruderCaptureMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_intruder_capture_dialog_title)) },
        text = {
            Column {
                IntruderCaptureMode.entries.forEach { mode ->
                    val label = stringResource(captureModeLabel(mode))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == mode, onClick = { onSelect(mode) })
                        Text(text = label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_screen_trusted_networks_close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun IntruderThresholdDialog(
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_intruder_threshold_dialog_title)) },
        text = {
            Column {
                (PreferencesRepository.MIN_INTRUDER_THRESHOLD..PreferencesRepository.MAX_INTRUDER_THRESHOLD)
                    .forEach { tries ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(tries) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == tries, onClick = { onSelect(tries) })
                            Text(
                                text = stringResource(
                                    R.string.settings_screen_intruder_threshold_option,
                                    tries
                                ),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_screen_trusted_networks_close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun ScreenTimeoutDialog(
    title: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PreferencesRepository.SCREEN_TIMEOUT_OPTIONS_SECONDS.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(seconds) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == seconds, onClick = { onSelect(seconds) })
                        Text(
                            text = screenTimeoutLabel(context, seconds),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_screen_trusted_networks_close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/** A screen timeout as Settings shows it: "15 seconds", "2 minutes". */
private fun screenTimeoutLabel(context: Context, seconds: Int): String =
    if (seconds < 60) {
        context.resources.getQuantityString(
            R.plurals.settings_screen_screen_timeout_seconds,
            seconds,
            seconds
        )
    } else {
        context.resources.getQuantityString(
            R.plurals.settings_screen_screen_timeout_minutes,
            seconds / 60,
            seconds / 60
        )
    }

@Composable
fun SecureSettingsGrantDialog(
    adbCommand: String,
    onGrantWithShizuku: () -> Unit,
    onCopyCommand: () -> Unit,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_lock_screen_content_grant_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_screen_lock_screen_content_grant_dialog_text),
                    style = MaterialTheme.typography.bodyMedium
                )
                FilledTonalButton(
                    onClick = onGrantWithShizuku,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_screen_lock_screen_content_grant_shizuku))
                }
                Text(
                    text = stringResource(R.string.settings_screen_lock_screen_content_grant_adb),
                    style = MaterialTheme.typography.bodyMedium
                )
                MonospaceBlock(adbCommand)
                TextButton(onClick = onCopyCommand) {
                    Text(stringResource(R.string.settings_screen_lock_screen_content_copy_command))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCheckAgain) {
                Text(stringResource(R.string.settings_screen_lock_screen_content_check_again))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/** Copies the adb grant command. It holds nothing secret, so unlike the token it isn't flagged sensitive. */
private fun copyCommandToClipboard(context: Context, command: String) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("adb command", command))
}

@Composable
fun IntruderEmailDialog(
    hasStoredKey: Boolean,
    from: String,
    to: String,
    onSave: (apiKey: String?, from: String, to: String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var fromField by remember { mutableStateOf(from) }
    var toField by remember { mutableStateOf(to) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_intruder_email_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_screen_intruder_email_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_screen_intruder_email_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (hasStoredKey) {
                        { Text(stringResource(R.string.settings_screen_intruder_email_api_key_saved)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fromField,
                    onValueChange = { fromField = it },
                    label = { Text(stringResource(R.string.settings_screen_intruder_email_from)) },
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.settings_screen_intruder_email_from_hint))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = toField,
                    onValueChange = { toField = it },
                    label = { Text(stringResource(R.string.settings_screen_intruder_email_to)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(apiKey.ifBlank { null }, fromField, toField) },
                enabled = toField.isNotBlank() && (hasStoredKey || apiKey.isNotBlank())
            ) {
                Text(stringResource(R.string.settings_screen_intruder_email_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun RemoteLockKeywordDialog(
    keyword: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var field by remember { mutableStateOf(keyword) }
    val valid = RemoteLock.isValidKeyword(field)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_screen_remote_lock_keyword_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_screen_remote_lock_keyword_dialog_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text(stringResource(R.string.settings_screen_remote_lock_keyword_label)) },
                    singleLine = true,
                    isError = field.isNotBlank() && !valid,
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.settings_screen_remote_lock_keyword_hint,
                                RemoteLock.MIN_KEYWORD_LENGTH
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(field) }, enabled = valid) {
                Text(stringResource(R.string.settings_screen_remote_lock_keyword_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_button))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

/** Whether a remote lock could lock the screen right now, through accessibility or device admin. */
private fun canLockPhone(context: Context): Boolean =
    AppLockAccessibilityService.connectedInstance != null || DeviceAdmin.hasForceLock(context)

/**
 * Opens Android's notification access page for the remote lock listener: its own switch from
 * Android 11, the list of apps before that or where a phone lacks the direct page.
 */
private fun openNotificationAccessSettings(context: Context) {
    val listPage = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            android.content.ComponentName(context, RemoteLockListener::class.java).flattenToString()
        )
    } else {
        listPage
    }
    try {
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(listPage)
        }
    } catch (e: Exception) {
        LogUtils.e("SettingsScreen", "Couldn't open the notification access page", e)
    }
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
