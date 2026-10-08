package org.starbridge.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.starbridge.core.astro.Site

/**
 * Position and UTC time from the phone's GPS (works offline, no SIM, no Google services).
 * The first fix outdoors can take a few minutes.
 */
class GpsSource(
    private val context: Context,
    /** [precise] = satellite fix (position and time trustworthy); false = network position. */
    private val onFix: (site: Site, accuracyM: Double?, precise: Boolean) -> Unit,
) : LocationListener {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** GPS UTC minus system clock, in ms. Zero until the first GPS fix. */
    @Volatile var clockOffsetMs = 0L
        private set

    @Volatile var hasFix = false
        private set

    @Volatile var running = false
        private set

    fun hasPermission() =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** UTC now, corrected with the GPS time when available. */
    fun now(): Long = System.currentTimeMillis() + clockOffsetMs

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !hasPermission()) return
        running = true
        // Registered even if Location is off right now: updates start when the user enables it.
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 0f, this, Looper.getMainLooper())
        }
        if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
            runCatching {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 0f, this, Looper.getMainLooper())
            }
        }
        // A last known network position is a good start while the satellites are found.
        runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()?.let { onLocationChanged(it) }
    }

    fun stop() {
        running = false
        runCatching { lm.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) {
            // Location.time is UTC from the satellites; age it with the monotonic clock.
            val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            if (ageMs in 0..60_000) clockOffsetMs = location.time + ageMs - System.currentTimeMillis()
        }
        // Keep the best fix: ignore worse network fixes once GPS is locked.
        if (hasFix && location.provider != LocationManager.GPS_PROVIDER) return
        if (location.provider == LocationManager.GPS_PROVIDER) hasFix = true
        onFix(
            Site(location.latitude, location.longitude),
            if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            location.provider == LocationManager.GPS_PROVIDER,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
