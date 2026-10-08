package org.starbridge.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Field rotation of an alt-azimuth mount, degrees per hour (0 for equatorial mounts). */
fun fieldRotationDegPerHour(latDeg: Double, azDeg: Double, altDeg: Double): Double {
    val c = cos(Math.toRadians(altDeg)).coerceAtLeast(0.02) // avoid the zenith singularity
    return 15.041 * cos(Math.toRadians(latDeg)) * cos(Math.toRadians(azDeg)) / c
}

data class ExposureInput(
    /** Exposure of the test frame(s), seconds. */
    val testExposureSec: Double,
    /** Background noise σ measured in the test frame (ADU, dark-subtracted not required). */
    val backgroundSigma: Double,
    /** Read noise σ (ADU) at the same ISO, from two shortest-exposure covered frames. */
    val readNoise: Double,
    val minExposureSec: Double,
    val maxExposureSec: Double,
    /** Field rotation (deg/h); 0 if unknown or equatorial. */
    val rotationDegPerHour: Double = 0.0,
    /** Distance from the rotation centre to the farthest used pixel. */
    val maxRadiusPx: Double = 1000.0,
    /** Measured drift (px/s) from registration history; 0 if unknown. */
    val driftPxPerSec: Double = 0.0,
    val fwhmPx: Double = 3.0,
    /** Fraction of detected stars saturated in the test frame. */
    val saturatedFraction: Double = 0.0,
    /** k: sky variance ≥ k × read-noise variance (10 ⇒ < 5 % loss vs infinite exposure). */
    val swamp: Double = 10.0,
    /** Dead time between exposures (readout + processing gap), seconds. */
    val overheadSec: Double = 0.3,
)

data class ExposureAdvice(val exposureSec: Double, val limitedBy: String, val explanation: String)

/**
 * Optimal sub-exposure: the shortest one where the sky noise swamps the read noise
 * (longer subs no longer improve the stack for a given total time), limited by the camera,
 * field rotation, drift and star saturation. Gain-free: works in ADU because both the sky
 * variance and the read-noise variance are measured in the same units.
 */
object ExposureAdvisor {
    fun advise(i: ExposureInput): ExposureAdvice {
        val skyVar = max(1e-9, i.backgroundSigma * i.backgroundSigma - i.readNoise * i.readNoise)
        // Sky variance grows linearly with time.
        val tSwamp = i.swamp * i.readNoise * i.readNoise * i.testExposureSec / skyVar
        // Keep the shutter open ≥ 90 % of the time: very short subs waste time in readout.
        val tMin = max(tSwamp, 9 * i.overheadSec)
        val limits = ArrayList<Pair<Double, String>>()
        limits += i.maxExposureSec to "el máximo de la cámara"
        if (i.rotationDegPerHour != 0.0) {
            val omegaRadPerSec = abs(i.rotationDegPerHour) * PI / 180 / 3600
            val thetaMax = 1.0 / i.maxRadiusPx // 1 px at the edge
            limits += (thetaMax / omegaRadPerSec) to "la rotación de campo"
        }
        if (i.driftPxPerSec > 0) limits += (0.5 * i.fwhmPx / i.driftPxPerSec) to "la deriva del seguimiento"
        if (i.saturatedFraction > 0.02) limits += (i.testExposureSec * 0.02 / i.saturatedFraction) to "las estrellas saturadas"
        val (upper, why) = limits.minBy { it.first }
        val wanted = tMin * 1.3
        val t = min(wanted, upper).coerceIn(i.minExposureSec, i.maxExposureSec)
        val limitedBy = if (wanted > upper) why else "el ruido del sensor (óptimo)"
        val explanation = if (wanted > upper) {
            "%.1f s · limitado por %s (lo ideal serían %.1f s)".format(t, why, wanted)
        } else {
            "%.1f s · el cielo ya tapa el ruido del sensor".format(t)
        }
        return ExposureAdvice(t, limitedBy, explanation)
    }

    /** Read noise (ADU) from two equal covered frames: σ(F1 − F2) / √2. */
    fun readNoise(a: Image, b: Image): Double {
        val n = min(a.data.size, b.data.size)
        val step = max(1, n / 200_000)
        val d = FloatArray(n / step + 1)
        var k = 0
        var i = 0
        while (i < n) { d[k++] = a.data[i] - b.data[i]; i += step }
        return Stats.clipped(d, k, 4f, 3).sigma / sqrt(2.0)
    }
}

/** Focus measures and the V-curve fit used by the focus assistant. */
object Focus {
    data class Measure(val hfr: Double, val uncertainty: Double, val stars: Int, val reliable: Boolean)

    /** Median HFR of several frames' star lists, with its uncertainty. */
    fun measure(starLists: List<List<Star>>): Measure {
        val perFrame = starLists.mapNotNull { list ->
            val good = list.filter { !it.saturated && it.snr > 10 }
            if (good.size < 3) null else Stats.median(good.map { it.hfr })
        }
        val stars = starLists.lastOrNull()?.count { !it.saturated && it.snr > 10 } ?: 0
        if (perFrame.isEmpty()) return Measure(Double.NaN, Double.NaN, stars, false)
        val m = Stats.median(perFrame)
        val unc = if (perFrame.size >= 2) Stats.madSigma(perFrame) / sqrt(perFrame.size.toDouble()) else m * 0.05
        return Measure(m, max(unc, m * 0.01), stars, stars >= 5)
    }

    /**
     * Hyperbola HFR(p) = sqrt(a² + b²(p − p0)²). For a fixed p0 the model is linear in
     * (a², b²): least squares; p0 is scanned finely. Returns p0 or null if the fit is poor.
     */
    fun bestPosition(positions: List<Double>, hfrs: List<Double>): Double? {
        if (positions.size < 4) return null
        val lo = positions.min()
        val hi = positions.max()
        var bestP = Double.NaN
        var bestErr = Double.MAX_VALUE
        val steps = 400
        for (s in 0..steps) {
            val p0 = lo + (hi - lo) * s / steps
            // Solve hfr² = A + B·(p − p0)² by least squares.
            var s11 = 0.0; var s12 = 0.0; var s22 = 0.0; var t1 = 0.0; var t2 = 0.0
            for (k in positions.indices) {
                val u = (positions[k] - p0) * (positions[k] - p0)
                val y = hfrs[k] * hfrs[k]
                s11 += 1.0; s12 += u; s22 += u * u; t1 += y; t2 += y * u
            }
            val det = s11 * s22 - s12 * s12
            if (abs(det) < 1e-12) continue
            val a = (t1 * s22 - t2 * s12) / det
            val b = (s11 * t2 - s12 * t1) / det
            if (a < 0 || b <= 0) continue
            var err = 0.0
            for (k in positions.indices) {
                val pred = sqrt(a + b * (positions[k] - p0) * (positions[k] - p0))
                err += (pred - hfrs[k]) * (pred - hfrs[k])
            }
            if (err < bestErr) { bestErr = err; bestP = p0 }
        }
        if (bestP.isNaN()) return positions[hfrs.indices.minBy { hfrs[it] }]
        return bestP
    }
}
