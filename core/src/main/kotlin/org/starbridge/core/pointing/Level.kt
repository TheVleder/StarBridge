package org.starbridge.core.pointing

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tilt of the mount's azimuth axis, measured with a phone lying on the tube (screen up, top
 * of the phone towards the front of the tube) while the mount turns in azimuth.
 *
 * With the axis leaning by the small vector T = (Tx east, Ty north), a tube facing azimuth a has
 * its front end lowered by T·f and its right side lowered by T·r, where f = (sin a, cos a) and
 * r = (cos a, −sin a) are its forward and right directions. So at each azimuth:
 *
 *     frontUp(a) = C1 − (Tx·sin a + Ty·cos a)       rightUp(a) = C2 − (Tx·cos a − Ty·sin a)
 *
 * C1, C2 hold the tube's altitude and however the phone lies on it: being constant they come
 * out of the fit, which is why the phone need not be placed precisely. Two azimuths 180° apart
 * already separate T from C; four give a check (rms).
 */
data class LevelResult(
    /** How far the azimuth axis leans from the vertical (degrees). */
    val tiltDeg: Double,
    /**
     * Where its top leans to, in the mount's azimuth frame (azSign·encoder azimuth): the sky
     * azimuth is this plus the alignment's azOffset.
     */
    val dirDeg: Double,
    val azSign: Int,
    /** Fit residual (degrees): small when the phone stayed put and the readings were steady. */
    val rmsDeg: Double,
    val at: Long,
) {
    /** Sky azimuth the axis leans towards, for an alignment with [azOffset] and [modelAzSign]. */
    fun towardAz(azOffset: Double, modelAzSign: Int = azSign) = ((modelAzSign * azSign * dirDeg + azOffset) % 360 + 360) % 360

    /** [PointingParams.tiltEast] and [PointingParams.tiltNorth] equivalent to this tilt. */
    fun tilts(azOffset: Double, modelAzSign: Int = azSign): Pair<Double, Double> {
        val az = Math.toRadians(towardAz(azOffset, modelAzSign))
        val t = Math.toRadians(tiltDeg)
        // The model's axis is Rx(tE)·Ry(tN)·up = (sin tN, −sin tE·cos tN, cos tE·cos tN).
        val tx = sin(t) * sin(az)
        val ty = sin(t) * cos(az)
        val tN = asin(tx.coerceIn(-1.0, 1.0))
        val tE = -asin((ty / cos(tN)).coerceIn(-1.0, 1.0))
        return Math.toDegrees(tE) to Math.toDegrees(tN)
    }
}

/** The phone's gravity at one azimuth of the mount ([aDeg] = azSign·encoder azimuth), in g. */
data class LevelSample(val aDeg: Double, val gx: Double, val gy: Double, val gz: Double)

object LevelFit {
    /** A phone tilted more than this is not lying on the tube as asked. */
    const val MAX_PHONE_TILT_DEG = 35.0

    /** (frontUp, rightUp) in degrees from a gravity vector in the phone's frame. */
    fun angles(gx: Double, gy: Double, gz: Double): Pair<Double, Double> {
        val n = sqrt(gx * gx + gy * gy + gz * gz).takeIf { it > 1e-9 } ?: return 0.0 to 0.0
        val front = Math.toDegrees(atan2(-gy, -gz))
        val right = Math.toDegrees(asin((-gx / n).coerceIn(-1.0, 1.0)))
        return front to right
    }

    /**
     * Undoes the phone's mean orientation on the tube in the order it physically has it: the
     * side-to-side roll (about the phone's long axis) first, then the pitch (tube altitude plus
     * how the phone lies, about the altitude axis). The readings that are left are the small
     * changes caused by the tilt, without the front and side readings mixing. (A plain shortest
     * rotation would leave a twist of roll·tan(pitch/2): over 1° with the tube 45° up.)
     */
    private fun straighten(samples: List<LevelSample>): (LevelSample) -> DoubleArray {
        fun unit(v: DoubleArray): DoubleArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) }
        val m = unit(samples.fold(DoubleArray(3)) { acc, s -> val g = unit(doubleArrayOf(s.gx, s.gy, s.gz)); doubleArrayOf(acc[0] + g[0], acc[1] + g[1], acc[2] + g[2]) })
        // About y: no sideways component left.
        val beta = kotlin.math.atan2(-m[0], -m[2])
        fun aboutY(v: DoubleArray) = doubleArrayOf(cos(beta) * v[0] - sin(beta) * v[2], v[1], sin(beta) * v[0] + cos(beta) * v[2])
        val m1 = aboutY(m)
        // Then about x: no forward component left, gravity straight down.
        val alpha = kotlin.math.atan2(-m1[1], -m1[2])
        fun aboutX(v: DoubleArray) = doubleArrayOf(v[0], cos(alpha) * v[1] - sin(alpha) * v[2], sin(alpha) * v[1] + cos(alpha) * v[2])
        return { s -> aboutX(aboutY(unit(doubleArrayOf(s.gx, s.gy, s.gz)))) }
    }

    /** Least squares over the positions; null if they cannot separate the tilt (< 2 azimuths). */
    fun fit(samples: List<LevelSample>, azSign: Int, at: Long): LevelResult? {
        if (samples.size < 2) return null
        val spread = samples.maxOf { s -> samples.maxOf { o -> kotlin.math.abs(PointingParams.angleDiff(s.aDeg, o.aDeg)) } }
        if (spread < 60) return null
        val straight = straighten(samples)
        // Unknowns x = (C1, C2, Tx, Ty), in degrees; two equations per position.
        val rows = ArrayList<DoubleArray>()
        val rhs = ArrayList<Double>()
        for (s in samples) {
            val g = straight(s)
            val (front, right) = angles(g[0], g[1], g[2])
            val a = Math.toRadians(s.aDeg)
            rows += doubleArrayOf(1.0, 0.0, -sin(a), -cos(a)); rhs += front
            rows += doubleArrayOf(0.0, 1.0, -cos(a), sin(a)); rhs += right
        }
        val x = leastSquares(rows, rhs) ?: return null
        var ss = 0.0
        rows.forEachIndexed { i, r -> val e = r.indices.sumOf { r[it] * x[it] } - rhs[i]; ss += e * e }
        val tx = x[2]
        val ty = x[3]
        return LevelResult(
            tiltDeg = hypot(tx, ty),
            dirDeg = ((Math.toDegrees(atan2(tx, ty)) % 360) + 360) % 360,
            azSign = azSign,
            rmsDeg = sqrt(ss / rows.size),
            at = at,
        )
    }

    private fun leastSquares(rows: List<DoubleArray>, rhs: List<Double>): DoubleArray? {
        val n = rows.first().size
        val a = Array(n) { i -> DoubleArray(n) { j -> rows.sumOf { it[i] * it[j] } } }
        val b = DoubleArray(n) { i -> rows.indices.sumOf { rows[it][i] * rhs[it] } }
        // Gaussian elimination with partial pivoting.
        val m = Array(n) { i -> a[i].copyOf() + b[i] }
        for (c in 0 until n) {
            val piv = (c until n).maxBy { kotlin.math.abs(m[it][c]) }
            if (kotlin.math.abs(m[piv][c]) < 1e-12) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) x[r] = (m[r][n] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
        return x
    }
}
