package org.starbridge.core.pointing

import org.starbridge.core.mount.AltAz
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * One alignment point: where the encoders were ([mount]) when the tube was really pointing
 * at [sky] (apparent topocentric Az/Alt).
 */
data class AlignmentPoint(
    val label: String,
    val mount: AltAz,
    val sky: AltAz,
    /** Expected error (degrees). Stars centred in the eyepiece ≈ 0.05; phone sensors ≈ 1–8. */
    val sigmaAz: Double,
    val sigmaAlt: Double,
    val fromSensor: Boolean = false,
)

data class AlignmentSolution(
    val params: PointingParams,
    /** Per-point error after the fit, arcminutes (stars only). */
    val residualsArcmin: List<Double>,
    val rmsArcmin: Double,
    val starCount: Int,
    val sensorOnly: Boolean,
)

/**
 * Least-squares fit (Levenberg–Marquardt with numerical Jacobian) of [PointingParams].
 * - 1 star: azimuth/altitude offsets only (assumes a level base).
 * - 2+ stars: offsets + both tilts. A weak prior keeps tilts small, which also resolves the
 *   azimuth-direction ambiguity (a wrong sign needs absurd tilts).
 * - A measured [LevelResult] (the phone on the tube): the tilts are known within
 *   [LEVEL_SIGMA], so a single star already gives the whole model.
 */
object AlignmentSolver {
    private const val TILT_PRIOR_SIGMA = 4.0 // degrees: tripods are rarely worse than that
    /** How well the phone measures the tilt (degrees). */
    const val LEVEL_SIGMA = 0.1

    fun solve(points: List<AlignmentPoint>, preferredAzSign: Int = 1, level: LevelResult? = null): AlignmentSolution? {
        if (points.isEmpty()) return null
        val stars = points.count { !it.fromSensor }
        val fitTilt = stars >= 2 || level != null
        val signs = if (stars >= 2) listOf(preferredAzSign, -preferredAzSign) else listOf(preferredAzSign)
        val best = signs.map { fit(points, it, fitTilt, level) }.minBy { it.second }.first

        val starPoints = points.filter { !it.fromSensor }
        val residuals = starPoints.map { PointingParams.separation(best.mountToSky(it.mount), it.sky) * 60.0 }
        val rms = if (residuals.isEmpty()) 0.0 else sqrt(residuals.sumOf { it * it } / residuals.size)
        return AlignmentSolution(best, residuals, rms, stars, stars == 0)
    }

    /** Returns params and the weighted cost. */
    private fun fit(points: List<AlignmentPoint>, azSign: Int, fitTilt: Boolean, level: LevelResult?): Pair<PointingParams, Double> {
        // Initial guess from the most trusted point, level base.
        val ref = points.minBy { it.sigmaAz + it.sigmaAlt }
        var p = doubleArrayOf(
            PointingParams.angleDiff(ref.sky.azDeg, azSign * ref.mount.azDeg),
            ref.sky.altDeg - ref.mount.altDeg,
            0.0,
            0.0,
        )
        val n = if (fitTilt) 4 else 2
        var lambda = 1e-3
        var cost = cost(points, p, azSign, fitTilt, level)
        repeat(100) {
            val r0 = residuals(points, p, azSign, fitTilt, level)
            val jac = Array(r0.size) { DoubleArray(n) }
            for (k in 0 until n) {
                val h = 1e-6
                val q = p.copyOf().also { it[k] += h }
                val r1 = residuals(points, q, azSign, fitTilt, level)
                for (i in r0.indices) jac[i][k] = (r1[i] - r0[i]) / h
            }
            // (JᵀJ + λ·diag) Δ = −Jᵀr
            val a = Array(n) { i -> DoubleArray(n) { j -> r0.indices.sumOf { jac[it][i] * jac[it][j] } } }
            val g = DoubleArray(n) { i -> -r0.indices.sumOf { jac[it][i] * r0[it] } }
            for (i in 0 until n) a[i][i] += lambda * (a[i][i] + 1e-9)
            val delta = solveLinear(a, g) ?: return@repeat
            val candidate = p.copyOf().also { for (i in 0 until n) it[i] += delta[i] }
            val newCost = cost(points, candidate, azSign, fitTilt, level)
            if (newCost < cost) {
                p = candidate
                val improvement = cost - newCost
                cost = newCost
                lambda = maxOf(lambda / 3, 1e-9)
                if (improvement < 1e-14 && delta.all { abs(it) < 1e-9 }) return toParams(p, azSign) to cost
            } else {
                lambda *= 5
                if (lambda > 1e8) return toParams(p, azSign) to cost
            }
        }
        return toParams(p, azSign) to cost
    }

    private fun toParams(p: DoubleArray, azSign: Int) =
        PointingParams(normalize180(p[0]), p[1], p[2], p[3], azSign)

    private fun normalize180(x: Double) = PointingParams.angleDiff(x, 0.0)

    private fun cost(points: List<AlignmentPoint>, p: DoubleArray, azSign: Int, fitTilt: Boolean, level: LevelResult?) =
        residuals(points, p, azSign, fitTilt, level).sumOf { it * it }

    private fun residuals(points: List<AlignmentPoint>, p: DoubleArray, azSign: Int, fitTilt: Boolean, level: LevelResult?): DoubleArray {
        val params = toParams(p, azSign).let { if (fitTilt) it else it.copy(tiltEast = 0.0, tiltNorth = 0.0) }
        val out = ArrayList<Double>(points.size * 2 + 2)
        for (pt in points) {
            val pred = params.mountToSky(pt.mount)
            val dAz = PointingParams.angleDiff(pred.azDeg, pt.sky.azDeg) * cos(Math.toRadians(pt.sky.altDeg))
            val dAlt = pred.altDeg - pt.sky.altDeg
            out += dAz / pt.sigmaAz
            out += dAlt / pt.sigmaAlt
        }
        if (level != null) {
            // The measured tilt, turned into this model's frame (it depends on the azimuth offset).
            val (tE, tN) = level.tilts(p[0], azSign)
            out += (p[2] - tE) / LEVEL_SIGMA
            out += (p[3] - tN) / LEVEL_SIGMA
        } else if (fitTilt) {
            out += p[2] / TILT_PRIOR_SIGMA
            out += p[3] / TILT_PRIOR_SIGMA
        }
        return out.toDoubleArray()
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() + b[i] }
        for (c in 0 until n) {
            val piv = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[piv][c]) < 1e-15) return null
            val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            x[r] = (m[r][n] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
        }
        return x
    }
}
