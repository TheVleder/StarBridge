package org.starbridge.app

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import org.starbridge.core.astro.Astro
import org.starbridge.core.astro.Site
import org.starbridge.core.control.OrientationSensor
import org.starbridge.core.mount.AltAz
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where the tube points according to the phone strapped to it: accelerometer (altitude,
 * ~0.5°) + compass (azimuth, a few degrees; disturbed by the metal mount) + gyroscope
 * (fused by Android's rotation vector). Corrected for magnetic declination.
 */
class TubeOrientationSensor(
    private val sensorManager: SensorManager,
    /** "top": phone's top edge towards the front of the tube; "camera": back camera looks through it. */
    private val mounting: () -> String,
    private val site: () -> Site,
    private val now: () -> Long,
) : OrientationSensor, SensorEventListener {

    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val available: Boolean get() = sensor != null

    private val rotation = FloatArray(9)
    // Running average of the pointing vector in (East, magnetic North, Up).
    private val avg = DoubleArray(3)
    @Volatile private var lastSampleAt = 0L
    @Volatile private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    private var declinationDeg = 0.0
    private var declinationComputedAt = 0L
    private var declinationSite: Site? = null

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun tubePointing(): AltAz? {
        if (SystemClock.elapsedRealtime() - lastSampleAt > 2_000) return null
        val (x, y, z) = synchronized(avg) { Triple(avg[0], avg[1], avg[2]) }
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1e-6) return null
        val alt = Math.toDegrees(asin((z / n).coerceIn(-1.0, 1.0)))
        val azMagnetic = Math.toDegrees(atan2(x, y))
        return AltAz(Astro.norm360(azMagnetic + declination()), alt)
    }

    /** Compass accuracy reported by Android (0 = unreliable … 3 = high). */
    val compassAccuracy: Int get() = accuracy

    private fun declination(): Double {
        val t = now()
        val s = site()
        if (t - declinationComputedAt > 3_600_000 || s != declinationSite) {
            declinationDeg = GeomagneticField(s.latitudeDeg.toFloat(), s.longitudeDeg.toFloat(), 0f, t).declination.toDouble()
            declinationComputedAt = t
            declinationSite = s
        }
        return declinationDeg
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Pointing direction in device coordinates.
        val d = if (mounting() == "camera") floatArrayOf(0f, 0f, -1f) else floatArrayOf(0f, 1f, 0f)
        val wx = rotation[0] * d[0] + rotation[1] * d[1] + rotation[2] * d[2]
        val wy = rotation[3] * d[0] + rotation[4] * d[1] + rotation[5] * d[2]
        val wz = rotation[6] * d[0] + rotation[7] * d[1] + rotation[8] * d[2]
        synchronized(avg) {
            avg[0] += ALPHA * (wx - avg[0])
            avg[1] += ALPHA * (wy - avg[1])
            avg[2] += ALPHA * (wz - avg[2])
        }
        lastSampleAt = SystemClock.elapsedRealtime()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        this.accuracy = accuracy
    }

    companion object {
        private const val ALPHA = 0.1
    }
}
