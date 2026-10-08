package org.starbridge.core.backlash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.MountDriver
import org.starbridge.core.pointing.PointingParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Backlash measured by hand (docs/HOLGURA.md): the Android rests on the tube and someone moves
 * the mount with the arrows from another phone. When an axis reverses, the motor turns through
 * the gear slack before the tube follows, so the meter reads how far the motor encoder went
 * between the button press and the moment the gyroscope sees the tube turn.
 *
 * Presses in the same direction as the previous one have no slack to take up: what they
 * measure is the lag of the system itself (sensor filter, motor start-up), and their median is
 * subtracted from the reversals.
 */
class SlackMeter(
    private val mount: MountDriver,
    private val sensor: MotionSensor,
    private val scope: CoroutineScope,
    /** Monotonic milliseconds. */
    private val now: () -> Long,
    private val maxTravelDeg: Double = MAX_TRAVEL_DEG,
) {
    enum class Kind { REVERSAL, SAME, FIRST }
    enum class Phase { IDLE, SLACK, MOVING, LOST }

    data class Measurement(val axis: Axis, val direction: Int, val rawDeg: Double, val kind: Kind)

    data class Summary(
        /** Gear slack: median of the reversals minus the lag. Null without reversals. */
        val slackDeg: Double?,
        val lagDeg: Double,
        /** Half the range of the reversals: how repeatable the measurement is. */
        val spreadDeg: Double,
        val reversals: Int,
    )

    data class Live(
        val axis: Axis?,
        val direction: Int,
        val phase: Phase,
        val travelDeg: Double,
        val gyroDegS: Double?,
        val thresholdDegS: Double?,
        /** Direction of the last move per axis that left the gears loaded (0 = unknown). */
        val loaded: Map<Axis, Int>,
    )

    private class Press(val axis: Axis, val direction: Int, val kind: Kind, val startEnc: AltAz, val startT: Long) {
        val encoder = ArrayList<Pair<Long, Double>>() // time, signed travel since the press
        @Volatile var phase = Phase.SLACK
        @Volatile var travel = 0.0
        @Volatile var baseline = 0.0
        @Volatile var threshold = 0.0
        @Volatile var low = 0.0
        @Volatile var aboveSince: Long? = null
        @Volatile var onsetT: Long? = null
        @Volatile var resultDeg: Double? = null
    }

    @Volatile var active = false
        private set
    @Volatile private var press: Press? = null
    private val loaded = ConcurrentHashMap<Axis, Int>()
    private val results = CopyOnWriteArrayList<Measurement>()
    private val gyro = ArrayDeque<Pair<Long, Double>>() // guarded by itself
    /** Guards [press], the jobs and [active] changes (callers come from several coroutines). */
    private val lock = Any()
    private var gyroJob: Job? = null
    private var encoderJob: Job? = null

    val measurements: List<Measurement> get() = results.toList()

    fun start() {
        synchronized(lock) {
            if (active) return
            active = true
            loaded.clear()
            gyroJob = scope.launch {
                while (isActive) {
                    sample()
                    delay(GYRO_SAMPLE_MS)
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            active = false
            gyroJob?.cancel()
            encoderJob?.cancel()
            press = null
        }
    }

    fun clear(axis: Axis? = null) {
        results.removeIf { axis == null || it.axis == axis }
    }

    /** Call right before the slew command is sent: the starting encoder must not include it. */
    suspend fun onPress(axis: Axis, direction: Int) {
        if (!active) return
        finishPress()
        val start = mount.getAltAz()
        if (!active) return // the mode was closed during the read
        val prev = loaded[axis] ?: 0
        val kind = when (prev) {
            0 -> Kind.FIRST
            direction -> Kind.SAME
            else -> Kind.REVERSAL
        }
        val p = Press(axis, direction, kind, start, now())
        p.encoder += p.startT to 0.0
        // The tube is at rest now: its gyro reading is the baseline the motion must rise above.
        val rest = synchronized(gyro) { gyro.filter { it.first >= p.startT - REST_WINDOW_MS }.map { it.second } }
        val base = if (rest.size >= MIN_REST_SAMPLES) rest.median() else 0.0
        val noise = if (rest.size >= MIN_REST_SAMPLES) rest.map { abs(it - base) }.median() * 1.4826 else sensor.noiseFloor
        p.baseline = base
        p.threshold = base + maxOf(NOISE_FACTOR * noise, MIN_RISE_DEG_S)
        p.low = base + maxOf(2 * noise, MIN_RISE_DEG_S / 2)
        synchronized(lock) {
            if (!active) return
            press = p
            encoderJob?.cancel()
            encoderJob = scope.launch { followEncoder(p) }
        }
    }

    /** The arrow was released (or the axis stopped). */
    fun onRelease(axis: Axis) {
        synchronized(lock) { if (press?.axis == axis) finishPress() }
    }

    private fun finishPress() {
        synchronized(lock) {
            val p = press ?: return
            press = null
            encoderJob?.cancel()
            // The gears end loaded on this side only if the tube really followed (or already was).
            loaded[p.axis] = if (p.resultDeg != null || p.kind == Kind.SAME) p.direction else 0
        }
    }

    private suspend fun followEncoder(p: Press) {
        while (press === p && active) {
            val enc = runCatching { mount.getAltAz() }.getOrNull()
            if (enc != null) {
                val signed = if (p.axis == Axis.AZM) PointingParams.angleDiff(enc.azDeg, p.startEnc.azDeg)
                else enc.altDeg - p.startEnc.altDeg
                val t = now()
                synchronized(p.encoder) { p.encoder += t to signed }
                p.travel = abs(signed)
                val onset = p.onsetT
                if (onset != null && p.resultDeg == null && t >= onset) {
                    val deg = travelAt(p, onset)
                    p.resultDeg = deg
                    p.phase = Phase.MOVING
                    results += Measurement(p.axis, p.direction, deg, p.kind)
                } else if (p.phase == Phase.SLACK && p.travel > maxTravelDeg) {
                    p.phase = Phase.LOST
                }
            }
            delay(ENCODER_POLL_MS)
        }
    }

    private fun sample() {
        val p = press
        val s = sensor.angularSpeed(p?.axis ?: Axis.AZM) ?: return
        val t = now()
        synchronized(gyro) {
            gyro.addLast(t to s)
            while (gyro.isNotEmpty() && gyro.first().first < t - GYRO_KEEP_MS) gyro.removeFirst()
        }
        if (p == null || p.phase != Phase.SLACK || p.onsetT != null) return
        if (s < p.threshold) {
            p.aboveSince = null
            return
        }
        val since = p.aboveSince ?: t.also { p.aboveSince = it }
        if (t - since < SUSTAIN_MS) return
        // Sustained rotation (not a knock): it started where the speed first left the noise.
        val history = synchronized(gyro) { gyro.filter { it.first >= p.startT && it.first <= since } }
        var onset = since
        for ((ts, v) in history.asReversed()) {
            if (v < p.low) break
            onset = ts
        }
        p.onsetT = onset
    }

    private fun travelAt(p: Press, t: Long): Double {
        val samples = synchronized(p.encoder) { p.encoder.toList() }
        // The first sample is the encoder read just before the slew was sent.
        if (samples.isEmpty() || t <= samples.first().first) return 0.0
        for (i in 1 until samples.size) {
            val (t2, a2) = samples[i]
            if (t2 >= t) {
                val (t1, a1) = samples[i - 1]
                val f = if (t2 == t1) 1.0 else (t - t1).toDouble() / (t2 - t1)
                return abs(a1 + (a2 - a1) * f)
            }
        }
        return abs(samples.last().second)
    }

    fun live(): Live {
        val p = press
        val g = synchronized(gyro) { gyro.lastOrNull()?.second }
        return Live(
            axis = p?.axis,
            direction = p?.direction ?: 0,
            phase = p?.phase ?: Phase.IDLE,
            travelDeg = p?.let { it.resultDeg ?: it.travel } ?: 0.0,
            gyroDegS = g,
            thresholdDegS = p?.threshold,
            loaded = Axis.entries.associateWith { loaded[it] ?: 0 },
        )
    }

    fun summary(axis: Axis): Summary {
        val mine = results.filter { it.axis == axis }
        val revs = mine.filter { it.kind == Kind.REVERSAL }.map { it.rawDeg }
        val same = mine.filter { it.kind == Kind.SAME }.map { it.rawDeg }
        val lag = if (same.isEmpty()) 0.0 else same.median()
        return Summary(
            slackDeg = if (revs.isEmpty()) null else maxOf(0.0, revs.median() - lag),
            lagDeg = lag,
            spreadDeg = if (revs.size < 2) 0.0 else (revs.max() - revs.min()) / 2,
            reversals = revs.size,
        )
    }

    companion object {
        const val GYRO_SAMPLE_MS = 20L
        const val ENCODER_POLL_MS = 40L
        const val GYRO_KEEP_MS = 4_000L
        const val REST_WINDOW_MS = 800L
        const val MIN_REST_SAMPLES = 8
        /** The tube must turn faster than the rest noise by this factor… */
        const val NOISE_FACTOR = 5.0
        /** …and by at least this much (deg/s): rate 4 turns at ~0.07°/s. */
        const val MIN_RISE_DEG_S = 0.03
        /** Above the threshold this long = real rotation, not a knock or a motor buzz. */
        const val SUSTAIN_MS = 150L
        /** More motor travel than this without the tube moving: the phone is not on the tube. */
        const val MAX_TRAVEL_DEG = 4.0

        private fun List<Double>.median(): Double = sorted().let { s ->
            if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
