package dev.pranav.applock.core.network

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.pranav.applock.core.broadcast.AppLockServiceStarter
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.services.isDeviceLocked
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the phone is on a trusted Wi-Fi network, so the lock backends can let locked apps
 * open there without authentication (see `AppLockRepository.isProtectionActive`).
 *
 * Fails closed throughout, since this switches locking off:
 *  - trust lives only in memory, so after a reboot or restart apps stay locked until Android
 *    reports a trusted network;
 *  - a network whose name can't be read - Location off, location access missing or limited to
 *    while the app is in use - counts as untrusted;
 *  - when the screen turns on or the phone unlocks the name is read again, and anything but a
 *    trusted name drops trust at once. That check only ever takes trust away; only Android's
 *    network updates grant it.
 *
 * Networks are matched by name alone, so mesh and dual-band networks need no extra setup.
 *
 * What Android reports is logged as it changes, never with network names, alongside the screen, app
 * and location state that decide what Android shares. The exported log then shows why trust was
 * kept or dropped.
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
object TrustedNetworkMonitor {
    private const val TAG = "TrustedNetworkMonitor"

    /** What Android reports in place of a Wi-Fi name it can't read or won't share. */
    private const val UNKNOWN_SSID = "<unknown ssid>"

    /** What [WifiInfo.getRssi] reports when there is no signal reading. */
    private const val INVALID_RSSI = -127

    data class State(
        /** The connected Wi-Fi's name as Android reports it (quoted), or null if none is readable. */
        val currentSsid: String? = null,
        val trusted: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Everything below is only touched while holding this object's lock.
    private var appContext: Context? = null
    private var networkCallback: WifiCallback? = null
    private var defaultNetworkCallback: DefaultNetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private val ssidsByNetwork = HashMap<Network, String>()

    // For the log only: the last report logged for each Wi-Fi network, and the network carrying
    // the phone's internet.
    private val reportsByNetwork = HashMap<Network, String>()
    private var defaultNetwork: Network? = null
    private var defaultNetworkReport: String? = null

    fun isOnTrustedNetwork(): Boolean = _state.value.trusted

    /**
     * Starts or stops monitoring to match the setting, then re-evaluates trust. Call it whenever the
     * setting, the trusted networks or location access may have changed.
     */
    @Synchronized
    fun refresh(context: Context) {
        val app = context.applicationContext
        appContext = app

        if (!app.appLockRepository().isTrustedWifiEnabled()) {
            stop(app)
            return
        }

        if (receiver == null) {
            registerReceiver(app)
            LogUtils.d(TAG, "Started monitoring Wi-Fi; ${describeConditions(app)}")
        }
        if (defaultNetworkCallback == null) {
            registerDefaultNetworkCallback(app)
        }
        // Once trusted there is nothing to gain. Otherwise ask again: location access may have been
        // granted since Android last reported the network, and it only reports again on a change.
        if (networkCallback == null || !_state.value.trusted) {
            registerNetworkCallback(app)
        }
        recompute(app)
    }

    /** The name to show for [ssid]. Android quotes names that are valid UTF-8. */
    fun displayName(ssid: String): String =
        if (ssid.length >= 2 && ssid.startsWith('"') && ssid.endsWith('"')) {
            ssid.substring(1, ssid.length - 1)
        } else {
            ssid
        }

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** Before Android 10 there is no separate background permission; location access covers it. */
    fun hasBackgroundLocationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    private fun stop(context: Context) {
        val wasRunning =
            receiver != null || networkCallback != null || defaultNetworkCallback != null
        unregisterNetworkCallback(context)
        unregisterDefaultNetworkCallback(context)
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                LogUtils.d(TAG, "Receiver was not registered")
            }
        }
        receiver = null
        updateState(context, State())
        if (wasRunning) LogUtils.d(TAG, "Stopped monitoring Wi-Fi")
    }

    /**
     * Registers a fresh callback in place of any earlier one. Android reports every current Wi-Fi
     * network to a new callback straight away. What the old one saw is dropped rather than carried
     * over: a network that has already gone is never reported to the new callback, so a leftover
     * entry for it would stay trusted.
     */
    private fun registerNetworkCallback(context: Context) {
        unregisterNetworkCallback(context)

        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            WifiCallback()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
            LogUtils.d(TAG, "Asked Android for the current Wi-Fi networks")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to register the Wi-Fi callback", e)
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        ssidsByNetwork.clear()
        reportsByNetwork.clear()
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to unregister the Wi-Fi callback", e)
        }
    }

    /** For the log only: follows which network carries the phone's internet. */
    private fun registerDefaultNetworkCallback(context: Context) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = DefaultNetworkCallback()
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            defaultNetworkCallback = callback
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to register the default network callback", e)
        }
    }

    private fun unregisterDefaultNetworkCallback(context: Context) {
        defaultNetwork = null
        defaultNetworkReport = null
        val callback = defaultNetworkCallback ?: return
        defaultNetworkCallback = null
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to unregister the default network callback", e)
        }
    }

    private fun registerReceiver(context: Context) {
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val app = receiverContext.applicationContext
                when (intent.action) {
                    LocationManager.MODE_CHANGED_ACTION -> onLocationModeChanged(app)
                    Intent.ACTION_SCREEN_ON -> checkOnScreenOn(app, "Screen on")
                    Intent.ACTION_USER_PRESENT -> checkOnScreenOn(app, "Unlock")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        try {
            ContextCompat.registerReceiver(
                context,
                newReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiver = newReceiver
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to register the screen and Location receiver", e)
        }
    }

    @Synchronized
    private fun onLocationModeChanged(context: Context) {
        if (networkCallback == null) return

        val enabled = isLocationEnabled(context)
        LogUtils.d(
            TAG,
            "Location turned ${if (enabled) "on" else "off"}; ${describeConditions(context)}"
        )

        // Off: no name can be read any more, so drop trust now instead of at the next network change.
        // On: a trusted network the phone is already on is only reported to a fresh callback.
        if (!enabled || !_state.value.trusted) {
            registerNetworkCallback(context)
            recompute(context)
        }
    }

    /** Takes trust away if the network read now isn't a trusted one. Never grants it. */
    @Synchronized
    private fun checkOnScreenOn(context: Context, event: String) {
        if (networkCallback == null || !_state.value.trusted) {
            LogUtils.d(
                TAG,
                "$event: not on a trusted network, nothing to check; ${describeConditions(context)}"
            )
            return
        }

        val trustedSsids = context.appLockRepository().getTrustedWifiSsids()
        val wifiInfo = readConnectionInfo(context)
        val ssid = readable(wifiInfo?.ssid)
        val reading = "Wi-Fi ${wifiInfo?.supplicantState?.name?.lowercase() ?: "unknown"}, " +
                "${describeName(ssid, trustedSsids)}, " +
                "${describeSignal(context, wifiInfo?.rssi)}${describeDbm(wifiInfo?.rssi)}"
        if (ssid != null && ssid in trustedSsids) {
            LogUtils.d(TAG, "$event check kept trust ($reading); ${describeConditions(context)}")
            return
        }

        LogUtils.d(
            TAG,
            "Screen-on check found no trusted network ($event: $reading); " +
                    describeConditions(context)
        )
        // A trusted network that really is there comes straight back through the fresh callback.
        registerNetworkCallback(context)
        recompute(context)
    }

    @Synchronized
    private fun onNetworkReport(
        callback: WifiCallback,
        network: Network,
        ssid: String?,
        validated: Boolean,
        rssi: Int?
    ) {
        if (callback !== networkCallback) return

        val readableSsid = readable(ssid)
        if (readableSsid == null) {
            ssidsByNetwork.remove(network)
        } else {
            ssidsByNetwork[network] = readableSsid
        }

        val context = appContext ?: return
        logNetworkReport(context, network, readableSsid, validated, rssi)
        recompute(context)
    }

    @Synchronized
    private fun onNetworkLost(callback: WifiCallback, network: Network) {
        if (callback !== networkCallback) return

        ssidsByNetwork.remove(network)
        reportsByNetwork.remove(network)
        appContext?.let {
            LogUtils.d(TAG, "Wi-Fi net $network gone; ${describeConditions(it)}")
            recompute(it)
        }
    }

    /** For the log only: notes when the phone's internet moves to another network. */
    @Synchronized
    private fun onDefaultNetwork(
        callback: DefaultNetworkCallback,
        network: Network,
        capabilities: NetworkCapabilities?
    ) {
        if (callback !== defaultNetworkCallback) return
        // A loss only matters for the network carrying internet. When internet moves to another
        // network, Android reports the new one instead.
        if (capabilities == null && network != defaultNetwork) return

        defaultNetwork = if (capabilities == null) null else network
        val report = when {
            capabilities == null -> "no network"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN net $network"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi net $network"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "mobile data net $network"

            else -> "other network net $network"
        }
        if (report == defaultNetworkReport) return
        defaultNetworkReport = report
        appContext?.let {
            LogUtils.d(TAG, "Internet now goes over $report; ${describeConditions(it)}")
        }
    }

    private fun recompute(context: Context) {
        val trustedSsids = context.appLockRepository().getTrustedWifiSsids()
        val ssids = ssidsByNetwork.values
        val trustedSsid = ssids.firstOrNull { it in trustedSsids }
        updateState(
            context,
            State(
                currentSsid = trustedSsid ?: ssids.firstOrNull(),
                trusted = networkCallback != null && trustedSsid != null
            )
        )
    }

    private fun updateState(context: Context, newState: State) {
        val wasTrusted = _state.value.trusted
        _state.value = newState
        if (newState.trusted == wasTrusted) return

        if (newState.trusted) {
            LogUtils.d(TAG, "On a trusted network, locked apps open without authentication")
        } else {
            LogUtils.d(TAG, "Not on a trusted network, re-locking")
            AppLockServiceStarter.relockAll(context, context.appLockRepository())
        }
    }

    private fun logNetworkReport(
        context: Context,
        network: Network,
        ssid: String?,
        validated: Boolean,
        rssi: Int?
    ) {
        val trustedSsids = context.appLockRepository().getTrustedWifiSsids()
        val report = "${describeName(ssid, trustedSsids)}, " +
                "internet ${if (validated) "verified" else "not verified"}, " +
                describeSignal(context, rssi)
        // Only changes are logged: Android reports a network again whenever its signal moves.
        if (reportsByNetwork.put(network, report) == report) return

        LogUtils.d(
            TAG,
            "Wi-Fi net $network: $report${describeDbm(rssi)}; ${describeConditions(context)}"
        )
    }

    /** Whether a readable name is a trusted one, without the name itself. */
    private fun describeName(ssid: String?, trustedSsids: Set<String>): String = when {
        ssid == null -> "no readable name"
        ssid in trustedSsids -> "trusted name"
        else -> "other name"
    }

    /** Signal in Android's bars, which move less often than the dBm reading. */
    @Suppress("DEPRECATION") // Per-device bar thresholds only exist from Android 11.
    private fun describeSignal(context: Context, rssi: Int?): String {
        if (rssi == null || rssi <= INVALID_RSSI) return "signal unknown"
        val wifiManager = context.getSystemService(WifiManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifiManager != null) {
            "signal ${wifiManager.calculateSignalLevel(rssi)}/${wifiManager.maxSignalLevel}"
        } else {
            "signal ${WifiManager.calculateSignalLevel(rssi, 5)}/4"
        }
    }

    private fun describeDbm(rssi: Int?): String =
        if (rssi == null || rssi <= INVALID_RSSI) "" else " at $rssi dBm"

    /** Screen, app and location state, which decide whether Android shares a network's name. */
    private fun describeConditions(context: Context): String {
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val app = when {
            process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
                "app in foreground"

            process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                "app as foreground service"

            process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
                "app visible"

            process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE ->
                "app perceptible"

            else -> "app in background"
        }
        val location = when {
            !hasLocationPermission(context) -> "no location access"
            !hasBackgroundLocationPermission(context) -> "location while in use"
            else -> "location all the time"
        }
        return listOf(
            if (screenOn) "screen on" else "screen off",
            if (context.isDeviceLocked()) "locked" else "unlocked",
            "$app (${process.importance})",
            location,
            if (isLocationEnabled(context)) "Location on" else "Location off"
        ).joinToString(", ")
    }

    private fun readable(ssid: String?): String? =
        ssid?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    /** The current Wi-Fi connection straight from [WifiManager]; the name needs location access. */
    @Suppress("DEPRECATION") // Still answers on Android 12+; the callback API has no synchronous read.
    private fun readConnectionInfo(context: Context): WifiInfo? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        return try {
            wifiManager.connectionInfo
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to read the Wi-Fi connection", e)
            null
        }
    }

    private class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Carries the name only because the callback asked for location info.
                capabilities.transportInfo as? WifiInfo
            } else {
                TrustedNetworkMonitor.appContext?.let { TrustedNetworkMonitor.readConnectionInfo(it) }
            }
            TrustedNetworkMonitor.onNetworkReport(
                this,
                network,
                ssid = wifiInfo?.ssid,
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                rssi = wifiInfo?.rssi
            )
        }

        override fun onLost(network: Network) {
            TrustedNetworkMonitor.onNetworkLost(this, network)
        }
    }

    /** For the log only: follows the network carrying the phone's internet. */
    private class DefaultNetworkCallback : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            TrustedNetworkMonitor.onDefaultNetwork(this, network, capabilities)
        }

        override fun onLost(network: Network) {
            TrustedNetworkMonitor.onDefaultNetwork(this, network, null)
        }
    }
}
