package org.starbridge.core.backlash

import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.pointing.PointingParams

/**
 * Gear slack measured by eye, with no reaction time involved: the same star is centred
 * several times, each time finishing with the last touch in the opposite direction.
 *
 * Finishing in the positive encoder direction leaves the motor slack/2 ahead of the tube,
 * finishing in the negative one slack/2 behind it. So for two consecutive centrings:
 *
 *     slack = (encoder after the positive finish − encoder after the negative finish)
 *           − (star position then − star position at the other centring)
 *
 * The star's own motion between the two is known, so it does not matter how long the user
 * takes. Every consecutive pair with opposite finishes gives one sample per axis.
 */
class CenteringSlack(val starId: String) {
    /**
     * One centring: the encoders (altitude signed), the star in the mount frame at that moment
     * and the encoder direction of the last movement of each axis (+1, −1, 0 = unknown).
     */
    data class Mark(val encoder: AltAz, val star: AltAz, val lastAz: Int, val lastAlt: Int, val at: Long)

    data class Estimate(val samples: List<Double>) {
        val slackDeg: Double? get() = samples.takeIf { it.isNotEmpty() }?.average()
        val spreadDeg: Double get() = if (samples.size < 2) 0.0 else samples.max() - samples.min()
    }

    private val marks = mutableListOf<Mark>()
    val count: Int get() = marks.size
    val last: Mark? get() = marks.lastOrNull()

    fun add(mark: Mark) {
        marks += mark
    }

    fun estimate(axis: Axis) = Estimate(marks.zipWithNext().mapNotNull { (a, b) -> sample(axis, a, b) })

    /** The encoder direction the next centring should finish in: the opposite of the last one. */
    fun next(axis: Axis): Int = last?.let { dirOf(axis, it) }?.takeIf { it != 0 }?.let { -it } ?: 1

    companion object {
        fun dirOf(axis: Axis, m: Mark) = if (axis == Axis.AZM) m.lastAz else m.lastAlt

        /** One measurement from two centrings, or null if they did not finish in opposite directions. */
        fun sample(axis: Axis, a: Mark, b: Mark): Double? {
            val da = dirOf(axis, a)
            val db = dirOf(axis, b)
            if (da == 0 || db == 0 || da == db) return null
            val (p, n) = if (da > 0) a to b else b to a
            return if (axis == Axis.AZM) {
                PointingParams.angleDiff(p.encoder.azDeg, n.encoder.azDeg) - PointingParams.angleDiff(p.star.azDeg, n.star.azDeg)
            } else {
                (p.encoder.altDeg - n.encoder.altDeg) - (p.star.altDeg - n.star.altDeg)
            }
        }
    }
}
