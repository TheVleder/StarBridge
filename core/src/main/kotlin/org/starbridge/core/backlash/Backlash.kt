package org.starbridge.core.backlash

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.Direction
import org.starbridge.core.mount.MountDriver
import kotlin.math.abs

/** What an axis is really doing, as seen by an external motion sensor. */
enum class AxisMotionState { IDLE, TAKING_UP_SLACK, MOVING }

/**
 * External sensor that sees the *tube* move (the mount's encoder only sees the motor).
 * On Android: the gyroscope of the phone strapped to the tube. See docs/HOLGURA.md.
 */
interface MotionSensor {
    /** Angular speed of the tube around [axis] in deg/s (absolute value), or null if unknown. */
    fun angularSpeed(axis: Axis): Double?

    /** Noise floor in deg/s, measured at rest. */
    val noiseFloor: Double
}

/**
 * Tracks, per axis, whether a commanded movement has really started moving the tube.
 */
class SlackDetector(private val sensor: MotionSensor, private val now: () -> Long = System::currentTimeMillis) {
    private val commandedAt = java.util.concurrent.ConcurrentHashMap<Axis, Long>()
    private val state = java.util.concurrent.ConcurrentHashMap<Axis, AxisMotionState>()

    /** Last measured dead time (ms) between command and real movement, per axis. */
    val lastDeadTimeMs = java.util.concurrent.ConcurrentHashMap<Axis, Long>()

    fun onSlewCommanded(axis: Axis) {
        commandedAt[axis] = now()
        state[axis] = AxisMotionState.TAKING_UP_SLACK
    }

    fun onStopCommanded(axis: Axis) {
        commandedAt.remove(axis)
        state[axis] = AxisMotionState.IDLE
    }

    /** Call periodically (e.g. every sensor sample). Returns the current state. */
    fun update(axis: Axis): AxisMotionState {
        val s = state[axis] ?: AxisMotionState.IDLE
        if (s == AxisMotionState.TAKING_UP_SLACK) {
            val speed = sensor.angularSpeed(axis) ?: return s
            if (speed > sensor.noiseFloor * THRESHOLD_FACTOR) {
                commandedAt[axis]?.let { lastDeadTimeMs[axis] = now() - it }
                state[axis] = AxisMotionState.MOVING
            }
        }
        return state[axis] ?: AxisMotionState.IDLE
    }

    companion object {
        const val THRESHOLD_FACTOR = 4.0
    }
}

data class CalibrationResult(
    val axis: Axis,
    /** Degrees of motor travel before the tube moved, per direction (median of runs). */
    val slackPositiveDeg: Double,
    val slackNegativeDeg: Double,
)

/**
 * Measures backlash automatically: reverses the axis and compares motor encoder travel with
 * the moment the external sensor sees the tube move. Always cancellable (coroutine cancel → STOP).
 */
class BacklashCalibrator(
    private val mount: MountDriver,
    private val sensor: MotionSensor,
    private val rate: Int = 5,
    private val runs: Int = 3,
    private val maxTravelDeg: Double = 5.0,
    private val pollMs: Long = 20,
) {
    suspend fun calibrate(axis: Axis): CalibrationResult {
        try {
            // Pre-load the gears in the negative direction so the first measurement starts from a known side.
            moveUntilTubeMoves(axis, Direction.NEGATIVE)
            val pos = mutableListOf<Double>()
            val neg = mutableListOf<Double>()
            repeat(runs) {
                pos += moveUntilTubeMoves(axis, Direction.POSITIVE)
                neg += moveUntilTubeMoves(axis, Direction.NEGATIVE)
            }
            return CalibrationResult(axis, pos.median(), neg.median())
        } finally {
            runCatching { mount.stop(axis) }
        }
    }

    /** Returns motor degrees travelled before the tube started to move. */
    private suspend fun moveUntilTubeMoves(axis: Axis, dir: Direction): Double {
        val start = mount.getMotorPosition(axis)
        mount.slew(axis, dir, rate)
        val moved = withTimeoutOrNull(30_000) {
            var travel: Double
            while (true) {
                val speed = sensor.angularSpeed(axis) ?: 0.0
                travel = angularDistance(mount.getMotorPosition(axis), start)
                if (travel > maxTravelDeg) error("Tube did not move after $maxTravelDeg° of motor travel")
                if (speed > sensor.noiseFloor * SlackDetector.THRESHOLD_FACTOR) break
                delay(pollMs)
            }
            travel
        } ?: error("Calibration timed out")
        mount.stop(axis)
        delay(300) // let the axis settle
        return moved
    }

    companion object {
        fun angularDistance(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return if (d > 180) 360 - d else d
        }

        /**
         * Converts measured slack to a native anti-backlash value (0..99).
         * [degPerUnit] is unknown for real SLT firmware: it has to be measured once on the real
         * mount (set a value, measure the rewind with MC_GET_POSITION). The default is a guess.
         */
        fun toNativeValue(slackDeg: Double, degPerUnit: Double = 0.01): Int =
            (slackDeg / degPerUnit).toInt().coerceIn(0, 99)

        private fun List<Double>.median(): Double = sorted().let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
