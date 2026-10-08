package org.starbridge.core.sim

import org.starbridge.core.astro.Astro
import org.starbridge.core.astro.Site
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.protocol.NexStarCodec
import org.starbridge.core.transport.SerialTransport
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Byte-level simulator of a NexStar SLT hand control + motors, for the unit tests and the
 * devserver (test fixtures: never in the APK).
 *
 * Realistic on purpose:
 * - Encoders count from the power-on position; the real sky direction depends on a hidden
 *   [geometry] (unknown azimuth/altitude zero and a tilted base).
 * - Gear backlash: each axis has a motor position (what the encoder reports) and a tube
 *   position (where the optics point). The tube follows only after [backlashDeg] of slack.
 * - GoTos stop when the *encoder* reaches the target, like the real hand control.
 * - When [aligned], the hand control "knows" [geometry] and reports real sky coordinates.
 */
class SimulatedHandController(
    site: Site = Site(40.4, -3.7),
    private val clock: () -> Long = System::currentTimeMillis,
    backlashDeg: Double = 0.6,
    var aligned: Boolean = true,
    private val version: Pair<Int, Int> = 4 to 21,
    /** Hidden mount geometry: raw tube angles → apparent sky. Identity = perfect north/level. */
    val geometry: PointingParams = PointingParams(),
    /** Like some old hand controls: answers `b` but does not move until it is aligned. */
    private val ignoresGotoUnaligned: Boolean = false,
    /** Azimuth motor wired the other way: a "positive" slew makes the encoder go down. */
    private val azMotorReversed: Boolean = false,
    /**
     * The hand control's "GoTo Approach" setting: +1/−1 = every `b` GoTo ends moving in that
     * direction on both axes (it overshoots and comes back if needed); 0 = straight there.
     */
    private val gotoApproach: Int = 0,
    /**
     * Like the SLT's small DC motors: the real speed wanders around the commanded one by up to
     * this fraction (changes every half second, reproducible). 0 = perfect motors.
     */
    private val speedNoise: Double = 0.0,
    /** Answer to `m`: 7 = SLT, 20 = AVX (equatorial)… */
    private val model: Int = 7,
) : SerialTransport {
    private val noise = kotlin.random.Random(7)

    var site: Site = site
        private set

    inner class SimAxis(var backlashDeg: Double) {
        var motorDeg = 0.0
        var tubeDeg = 0.0
        var rateDegPerSec = 0.0
        var nativePos = 0
        var nativeNeg = 0
        var lastDirection = 0.0
        /** Encoder target of a running GoTo. */
        var gotoTarget: Double? = null
        /** Where the GoTo really ends after its approach leg ([gotoApproach]). */
        var finalTarget: Double? = null
        var wraps = false

        val gotoRunning get() = gotoTarget != null || finalTarget != null

        fun cancelGoto() {
            gotoTarget = null
            finalTarget = null
        }

        fun commandRate(newRate: Double) {
            val dir = sign(newRate)
            if (dir != 0.0 && lastDirection != 0.0 && dir != lastDirection) {
                // Native anti-backlash: quick rewind on direction change.
                val comp = if (dir > 0) nativePos else nativeNeg
                motorDeg += dir * comp * DEG_PER_COMP_UNIT
                takeUpSlack()
            }
            if (dir != 0.0) lastDirection = dir
            rateDegPerSec = newRate
        }

        fun advance(dtSec: Double) {
            gotoTarget?.let { target ->
                val delta = if (wraps) shortest(target - motorDeg) else target - motorDeg
                if (abs(delta) < 1e-5) {
                    gotoTarget = finalTarget
                    finalTarget = null
                    if (gotoTarget == null) commandRate(0.0)
                } else {
                    commandRate(sign(delta) * min(GOTO_RATE, abs(delta) / maxOf(dtSec, 1e-3)))
                }
            }
            if (speedNoise > 0 && gotoTarget == null) {
                // The real speed wanders: a new random factor every half second.
                jitterLeft -= dtSec
                if (jitterLeft <= 0) {
                    jitter = 1 + speedNoise * (2 * noise.nextDouble() - 1)
                    jitterLeft = 0.5
                }
                motorDeg += rateDegPerSec * dtSec * jitter
            } else {
                motorDeg += rateDegPerSec * dtSec
            }
            takeUpSlack()
        }

        private var jitter = 1.0
        private var jitterLeft = 0.0

        private fun takeUpSlack() {
            val half = backlashDeg / 2
            val gap = motorDeg - tubeDeg
            if (gap > half) tubeDeg = motorDeg - half
            if (gap < -half) tubeDeg = motorDeg + half
        }
    }

    val azm = SimAxis(backlashDeg).also { it.wraps = true }
    val alt = SimAxis(backlashDeg)
    /** The `t`/`T` tracking mode (tests may set it, like the user on the hand control). */
    var tracking = 1
    var timeBytes: List<Int>? = null
        private set
    var syncCount = 0
        private set
    private var lastUpdate = clock()
    private val output = ArrayDeque<Int>()

    /** Where the optics really point (apparent sky). */
    fun truePointing(): AltAz {
        advance()
        return geometry.mountToSky(AltAz(azm.tubeDeg, alt.tubeDeg))
    }

    /** Test helper: put the tube (and encoders, no slack) at a raw mount position. */
    fun placeRaw(azRaw: Double, altRaw: Double) {
        azm.motorDeg = azRaw; azm.tubeDeg = azRaw
        alt.motorDeg = altRaw; alt.tubeDeg = altRaw
    }

    /**
     * Test helper: point the tube exactly at an apparent sky position (as a perfect user would),
     * arriving like a real move: the gears end loaded in the direction it came from, or in
     * [fromAz]/[fromAlt] (+1/−1 encoder direction of the last touch) when given.
     */
    fun centerOn(sky: AltAz, fromAz: Int = 0, fromAlt: Int = 0) {
        val raw = geometry.skyToMount(sky)
        val az = azm.motorDeg + shortest(raw.azDeg - azm.motorDeg)
        arrive(azm, az, fromAz)
        arrive(alt, raw.altDeg, fromAlt)
    }

    private fun arrive(axis: SimAxis, tube: Double, from: Int = 0) {
        val dir = if (from != 0) sign(from.toDouble()) else sign(tube - axis.tubeDeg)
        axis.tubeDeg = tube
        axis.motorDeg = tube + dir * axis.backlashDeg / 2
        if (dir != 0.0) axis.lastDirection = dir
    }

    fun axis(a: Axis) = if (a == Axis.AZM) azm else alt

    private fun advance() {
        val now = clock()
        val dt = (now - lastUpdate) / 1000.0
        lastUpdate = now
        if (dt > 0) {
            azm.advance(dt)
            alt.advance(dt)
        }
    }

    // --- SerialTransport ---------------------------------------------------

    override suspend fun write(data: ByteArray) {
        advance()
        handle(data.map { it.toInt() and 0xFF })
    }

    override suspend fun read(timeoutMs: Long): Int? = output.removeFirstOrNull()

    override suspend fun clearInput() = output.clear()

    override fun close() {}

    // --- Protocol ----------------------------------------------------------

    private fun reply(vararg bytes: Int) {
        bytes.forEach { output.addLast(it) }
        output.addLast(NexStarCodec.TERMINATOR)
    }

    private fun replyText(s: String) = reply(*s.map { it.code }.toIntArray())

    /** Encoder position in the frame the hand control reports. */
    private fun reportedAltAz(): AltAz {
        val raw = AltAz(azm.motorDeg, alt.motorDeg)
        return if (aligned) geometry.mountToSky(raw) else AltAz(Astro.norm360(raw.azDeg), raw.altDeg)
    }

    private fun startGotoRaw(raw: AltAz) {
        val az = Astro.norm360(raw.azDeg)
        val alt = NexStarCodec.signedDeg(Astro.norm360(raw.altDeg))
        if (gotoApproach == 0) {
            azm.gotoTarget = az
            this.alt.gotoTarget = alt
        } else {
            azm.gotoTarget = Astro.norm360(az - gotoApproach * HC_APPROACH_DEG)
            azm.finalTarget = az
            this.alt.gotoTarget = alt - gotoApproach * HC_APPROACH_DEG
            this.alt.finalTarget = alt
        }
    }

    private fun handle(b: List<Int>) {
        when (b.firstOrNull()?.toChar()) {
            'K' -> reply(b[1])
            'V' -> reply(version.first, version.second)
            'm' -> reply(model)
            'J' -> reply(if (aligned) 1 else 0)
            'L' -> replyText(if (azm.gotoRunning || alt.gotoRunning) "1" else "0")
            'M' -> {
                azm.cancelGoto(); alt.cancelGoto()
                azm.commandRate(0.0); alt.commandRate(0.0)
                reply()
            }
            'e' -> Astro.apparentToRaDec(reportedAltAz(), site, clock()).let {
                replyText("${NexStarCodec.degToHex32(it.raHours * 15)},${NexStarCodec.degToHex32(it.decDeg)}")
            }
            'z' -> reportedAltAz().let {
                replyText("${NexStarCodec.degToHex32(it.azDeg)},${NexStarCodec.degToHex32(it.altDeg)}")
            }
            'r' -> {
                if (aligned) {
                    val (ra, dec) = NexStarCodec.parseAnglePair(text(b))
                    val sky = Astro.apparentAltAz(
                        org.starbridge.core.mount.RaDec(ra / 15, NexStarCodec.signedDeg(dec)), site, clock(),
                    )
                    startGotoRaw(geometry.skyToMount(sky))
                }
                reply()
            }
            'b' -> {
                val (az, altDeg) = NexStarCodec.parseAnglePair(text(b))
                val target = AltAz(az, NexStarCodec.signedDeg(altDeg))
                if (aligned || !ignoresGotoUnaligned) startGotoRaw(if (aligned) geometry.skyToMount(target) else target)
                reply()
            }
            's' -> { syncCount++; reply() }
            'w' -> reply(*NexStarCodec.encodeLocation(site.latitudeDeg, site.longitudeDeg).map { it.toInt() and 0xFF }.toIntArray())
            'W' -> {
                val (lat, lon) = NexStarCodec.decodeLocation(ByteArray(8) { b[it + 1].toByte() })
                site = Site(lat, lon)
                reply()
            }
            'H' -> { timeBytes = b.drop(1).take(8); reply() }
            't' -> reply(tracking)
            'T' -> { tracking = b[1]; reply() }
            'P' -> passThrough(b)
            else -> {} // unknown: no answer → driver timeout, like the real HC
        }
    }

    private fun text(b: List<Int>) = b.drop(1).map { it.toChar() }.joinToString("")

    private fun passThrough(b: List<Int>) {
        val device = b[2]
        val msgId = b[3]
        val respLen = b[7]
        val axis = when (device) {
            Axis.AZM.deviceId -> azm
            Axis.ALT.deviceId -> alt
            else -> null
        }
        if (axis == null) {
            // No such device: garbage + one extra byte before '#'.
            reply(*IntArray(respLen + 1))
            return
        }
        val wiring = if (axis === azm && azMotorReversed) -1.0 else 1.0
        when (msgId) {
            NexStarCodec.MC_MOVE_POS, NexStarCodec.MC_MOVE_NEG -> {
                axis.cancelGoto()
                val s = wiring * if (msgId == NexStarCodec.MC_MOVE_POS) 1.0 else -1.0
                axis.commandRate(s * FIXED_RATES[b[4].coerceIn(0, 9)])
                reply()
            }
            NexStarCodec.MC_VAR_RATE_POS, NexStarCodec.MC_VAR_RATE_NEG -> {
                axis.cancelGoto()
                val s = wiring * if (msgId == NexStarCodec.MC_VAR_RATE_POS) 1.0 else -1.0
                axis.commandRate(s * ((b[4] shl 8) or b[5]) / 4.0 / 3600.0)
                reply()
            }
            NexStarCodec.MC_GET_VER -> reply(7, 11)
            NexStarCodec.MC_GET_POSITION -> reply(*NexStarCodec.degToBytes24(axis.motorDeg).map { it.toInt() and 0xFF }.toIntArray())
            NexStarCodec.MC_SLEW_DONE -> reply(if (axis.gotoRunning) 0x00 else 0xFF)
            NexStarCodec.MC_GET_POS_BACKLASH -> reply(axis.nativePos)
            NexStarCodec.MC_GET_NEG_BACKLASH -> reply(axis.nativeNeg)
            NexStarCodec.MC_SET_POS_BACKLASH -> { axis.nativePos = b[4].coerceIn(0, 99); reply() }
            NexStarCodec.MC_SET_NEG_BACKLASH -> { axis.nativeNeg = b[4].coerceIn(0, 99); reply() }
            else -> reply(*IntArray(respLen + 1))
        }
    }

    companion object {
        /** Approximate SLT fixed rates in deg/s for hand-control rates 0..9. */
        val FIXED_RATES = doubleArrayOf(0.0, 0.0083, 0.0167, 0.0333, 0.0667, 0.133, 0.5, 1.0, 2.0, 4.0)

        /** Simulated degrees of motor rewind per unit of native anti-backlash (0..99). */
        const val DEG_PER_COMP_UNIT = 0.01

        private const val GOTO_RATE = 4.0

        /** How far before the target the hand control's approach leg stops. */
        const val HC_APPROACH_DEG = 0.5

        private fun shortest(d: Double): Double {
            val r = Astro.norm360(d)
            return if (r > 180) r - 360 else r
        }
    }
}
