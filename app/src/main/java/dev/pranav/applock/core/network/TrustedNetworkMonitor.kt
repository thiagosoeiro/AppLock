package dev.pranav.applock.core.network

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.pranav.applock.core.broadcast.AppLockServiceStarter
import dev.pranav.applock.core.utils.LogUtils
import dev.pranav.applock.core.utils.appLockRepository
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
 */
@SuppressLint("StaticFieldLeak") // Holds the application context only.
object TrustedNetworkMonitor {
    private const val TAG = "TrustedNetworkMonitor"

    /** What Android reports in place of a Wi-Fi name it can't read or won't share. */
    private const val UNKNOWN_SSID = "<unknown ssid>"

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
    private var receiver: BroadcastReceiver? = null
    private val ssidsByNetwork = HashMap<Network, String>()

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
            LogUtils.d(TAG, "Started monitoring Wi-Fi")
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
        val wasRunning = receiver != null || networkCallback != null
        unregisterNetworkCallback(context)
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
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to register the Wi-Fi callback", e)
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        ssidsByNetwork.clear()
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to unregister the Wi-Fi callback", e)
        }
    }

    private fun registerReceiver(context: Context) {
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val app = receiverContext.applicationContext
                when (intent.action) {
                    LocationManager.MODE_CHANGED_ACTION -> onLocationModeChanged(app)
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> checkOnScreenOn(app)
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
        LogUtils.d(TAG, "Location turned ${if (enabled) "on" else "off"}")

        // Off: no name can be read any more, so drop trust now instead of at the next network change.
        // On: a trusted network the phone is already on is only reported to a fresh callback.
        if (!enabled || !_state.value.trusted) {
            registerNetworkCallback(context)
            recompute(context)
        }
    }

    /** Takes trust away if the network name read now isn't a trusted one. Never grants it. */
    @Synchronized
    private fun checkOnScreenOn(context: Context) {
        if (networkCallback == null || !_state.value.trusted) return

        val ssid = readConnectionSsid(context)
        if (ssid != null && ssid in context.appLockRepository().getTrustedWifiSsids()) return

        LogUtils.d(TAG, "Screen-on check found no trusted network")
        // A trusted network that really is there comes straight back through the fresh callback.
        registerNetworkCallback(context)
        recompute(context)
    }

    @Synchronized
    private fun onNetworkSsid(callback: WifiCallback, network: Network, ssid: String?) {
        if (callback !== networkCallback) return

        val readableSsid = readable(ssid)
        if (readableSsid == null) {
            ssidsByNetwork.remove(network)
        } else {
            ssidsByNetwork[network] = readableSsid
        }
        appContext?.let { recompute(it) }
    }

    @Synchronized
    private fun onNetworkLost(callback: WifiCallback, network: Network) {
        if (callback !== networkCallback) return

        ssidsByNetwork.remove(network)
        appContext?.let { recompute(it) }
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

    private fun readable(ssid: String?): String? =
        ssid?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    /** The connected Wi-Fi's name straight from [WifiManager], or null if none is readable. */
    @Suppress("DEPRECATION") // Still answers on Android 12+; the callback API has no synchronous read.
    private fun readConnectionSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return null
        return try {
            readable(wifiManager.connectionInfo?.ssid)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to read the Wi-Fi name", e)
            null
        }
    }

    private class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Carries the name only because the callback asked for location info.
                (capabilities.transportInfo as? WifiInfo)?.ssid
            } else {
                TrustedNetworkMonitor.appContext?.let { TrustedNetworkMonitor.readConnectionSsid(it) }
            }
            TrustedNetworkMonitor.onNetworkSsid(this, network, ssid)
        }

        override fun onLost(network: Network) {
            TrustedNetworkMonitor.onNetworkLost(this, network)
        }
    }
}
