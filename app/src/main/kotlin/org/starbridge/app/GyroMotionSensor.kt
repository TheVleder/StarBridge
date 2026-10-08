package org.starbridge.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.starbridge.core.backlash.MotionSensor
import org.starbridge.core.imaging.GyroWindow
import org.starbridge.core.mount.Axis
import kotlin.math.sqrt

/**
 * Gyroscope of the Android phone strapped to the telescope tube (docs/HOLGURA.md).
 * Only one axis moves at a time when detecting slack, so the total angular speed is used for
 * both axes: no need to know how the phone is mounted.
 */
class GyroMotionSensor(private val sensorManager: SensorManager) : MotionSensor, GyroWindow, SensorEventListener {
    // Shake during a camera exposure (RMS of the angular speed), for the frame filter.
    private val window = Any()
    private var windowOn = false
    private var windowSum = 0.0
    private var windowN = 0

    override fun begin() = synchronized(window) { windowOn = true; windowSum = 0.0; windowN = 0 }

    override fun end(): Double? = synchronized(window) {
        windowOn = false
        if (windowN == 0) null else kotlin.math.sqrt(windowSum / windowN)
    }

    @Volatile private var smoothed = 0.0
    @Volatile private var floor = DEFAULT_FLOOR
    private var samples = 0
    private var floorAcc = 0.0

    val available: Boolean = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    override val noiseFloor: Double get() = floor

    fun start() {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun angularSpeed(axis: Axis): Double? = if (samples > 0) smoothed else null

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val degPerSec = sqrt((x * x + y * y + z * z).toDouble()) * 180.0 / Math.PI
        smoothed += ALPHA * (degPerSec - smoothed)
        synchronized(window) { if (windowOn) { windowSum += degPerSec * degPerSec; windowN++ } }
        samples++
        // The first second at rest gives the noise floor.
        if (samples in 10..60) {
            floorAcc += smoothed
            if (samples == 60) floor = maxOf(DEFAULT_FLOOR, floorAcc / 51 * 1.5)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val ALPHA = 0.2
        private const val DEFAULT_FLOOR = 0.02
    }
}
