package org.starbridge.core.backlash

import org.starbridge.core.pointing.PointingParams

/**
 * Where the motor stands inside the gear play of one axis (docs/HOLGURA.md).
 *
 * The encoder is on the motor; the tube only follows once the play is taken up. [offset] is
 * encoder − tube and always stays within ±slack/2: +slack/2 after moving far enough in the
 * positive encoder direction, −slack/2 after moving far enough the other way, anything in
 * between after a short move. Every encoder reading is fed to [observe], so short taps,
 * reversals and partial moves are all accounted for (not only "the last direction").
 */
class GearPlay(private val wraps: Boolean) {
    @Volatile var slack = 0.0
        set(value) {
            field = value
            synchronized(this) { offset = offset.coerceIn(-value / 2, value / 2) }
        }

    @Volatile var offset = 0.0
        private set

    /** False until a move has taken up all the play: right after connecting it could be anywhere. */
    @Volatile var known = false
        private set

    private var last: Double? = null

    /** A new encoder reading of this axis (readings must arrive in the order they were taken). */
    @Synchronized
    fun observe(encoder: Double) {
        val prev = last
        last = encoder
        if (prev == null) return
        val d = if (wraps) PointingParams.angleDiff(encoder, prev) else encoder - prev
        if (d == 0.0) return
        val half = slack / 2
        val raw = offset + d
        if (raw >= half || raw <= -half) known = true
        offset = raw.coerceIn(-half, half)
    }

    /** A move we did not see step by step (a calibration) left the gears loaded in [dir]. */
    @Synchronized
    fun load(dir: Int) {
        if (dir == 0) return
        offset = dir * slack / 2
        known = true
    }

    /** New connection: the motors may have been moved by hand or power-cycled. */
    @Synchronized
    fun reset() {
        offset = 0.0
        known = false
        last = null
    }

    /** Where the tube is for this encoder angle. */
    fun tube(encoder: Double) = encoder - offset

    /** Encoder angle that puts the tube at [tube] once the gears are loaded moving in [dir]. */
    fun encoderFor(tube: Double, dir: Int) = tube + dir * slack / 2

    /** Motor travel still needed in [dir] before the tube follows: 0 if already loaded or unknown. */
    fun needed(dir: Int): Double = if (!known || dir == 0) 0.0 else (slack / 2 - dir * offset).coerceAtLeast(0.0)

    override fun toString() = "offset=${"%.3f".format(offset)} slack=$slack known=$known"
}
