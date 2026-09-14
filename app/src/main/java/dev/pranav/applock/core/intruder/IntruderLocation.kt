package dev.pranav.applock.core.intruder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.pranav.applock.core.network.TrustedNetworkMonitor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Finds where the phone is for an intruder alert, as a line of email text with a map link. It uses
 * the location access trusted Wi-Fi already asks for and Android's own location providers, so it
 * adds no permission and needs no Play services.
 */
object IntruderLocation {

    private const val FIX_TIMEOUT_MS = 10_000L

    /** Approximate location is enough for a map link, so either permission will do. */
    fun hasPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** A line for the email: a map link with its accuracy and age, or why there is none. */
    suspend fun describe(context: Context): String {
        if (!hasPermission(context)) return "Location: unavailable, no location permission"
        if (!TrustedNetworkMonitor.isLocationEnabled(context)) {
            return "Location: unavailable, Location is off"
        }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return "Location: unavailable"

        val location = try {
            currentLocation(context, manager) ?: lastKnownLocation(manager)
        } catch (_: SecurityException) {
            // With approximate location only, a provider that needs precise location refuses.
            lastKnownLocation(manager)
        }
        return location?.let(::format)
            ?: "Location: unavailable, no fix within ${FIX_TIMEOUT_MS / 1000} s"
    }

    @SuppressLint("MissingPermission") // describe checks the permission first.
    private suspend fun currentLocation(context: Context, manager: LocationManager): Location? {
        val provider = listOfNotNull(
            // From Android 12 the fused provider blends GPS, Wi-Fi and cell for the quickest good fix.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).firstOrNull {
            LocationManagerCompat.hasProvider(manager, it) && manager.isProviderEnabled(it)
        } ?: return null

        val signal = CancellationSignal()
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // describe checks the permission first.
    private fun lastKnownLocation(manager: LocationManager): Location? =
        manager.getProviders(true)
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.elapsedRealtimeNanos }

    private fun format(location: Location): String {
        val latitude = String.format(Locale.US, "%.6f", location.latitude)
        val longitude = String.format(Locale.US, "%.6f", location.longitude)
        val accuracy =
            if (location.hasAccuracy()) ", within ${location.accuracy.roundToInt()} m" else ""
        val ageSeconds = TimeUnit.NANOSECONDS.toSeconds(
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0L)
        )
        val age = if (ageSeconds < 120) "$ageSeconds s" else "${ageSeconds / 60} min"
        return "Location: https://maps.google.com/?q=$latitude,$longitude$accuracy, fix $age old"
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
