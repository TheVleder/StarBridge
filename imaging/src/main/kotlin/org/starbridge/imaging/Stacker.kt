package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Live stack: per-pixel, per-channel **weighted incremental mean and variance (Welford)**
 * with κσ rejection once a pixel has 5 samples. Satellites, planes, cosmic rays and residual
 * hot pixels are rejected without keeping every frame in memory. The first [WARMUP] frames
 * are kept and re-stacked with rejection when the 5th arrives, so early outliers go too.
 */
class Stacker(val width: Int, val height: Int, val channels: Int, private val kappa: Float = 3.0f) {
    private val n = width * height
    private val mean = Array(channels) { FloatArray(n) }
    private val m2 = Array(channels) { FloatArray(n) }
    private val wsum = Array(channels) { FloatArray(n) }
    private val count = Array(channels) { ShortArray(n) }
    /** Samples rejected per pixel (diagnostics: should look like trails, never like stars). */
    val rejected = IntArray(n)

    var frames = 0
        private set

    private class Pending(val frame: Frame, val coverage: FloatArray, val weight: Double, val floor: Float)
    private val warmup = ArrayList<Pending>()

    /**
     * Adds a registered frame. [coverage] is 0..1 per pixel (outside the frame = 0),
     * [weight] the frame weight, [sigmaFloor] the minimum σ used for rejection (the frame's
     * background noise: avoids cutting good samples when the per-pixel σ is underestimated).
     */
    fun add(frame: Frame, coverage: FloatArray, weight: Double, sigmaFloor: Float = 0f) {
        require(frame.channels.size == channels && frame.width == width && frame.height == height)
        frames++
        if (frames <= WARMUP) {
            warmup.add(Pending(frame, coverage, weight, sigmaFloor))
            accumulate(frame, coverage, weight, sigmaFloor, reject = false)
            return
        }
        if (frames == WARMUP + 1) restackWarmup()
        accumulate(frame, coverage, weight, sigmaFloor, reject = true)
    }

    /**
     * Re-stacks the warm-up frames with rejection. With so few samples a mean/σ test fails
     * (two bad samples out of five inflate σ), so each pixel uses the **median and MAD** of
     * its warm-up luminance samples, robust to up to 2 outliers. A sample is rejected (in all
     * channels) if its luminance is > 3·max(σ_robust, background noise) from the median.
     */
    private fun restackWarmup() {
        for (c in 0 until channels) { mean[c].fill(0f); m2[c].fill(0f); wsum[c].fill(0f); count[c].fill(0) }
        val k = warmup.size
        val lum = warmup.map { it.frame.luminance.data }
        val vals = FloatArray(k)
        val dev = FloatArray(k)
        val floor = warmup.map { it.floor }.average().toFloat()
        val keep = Array(k) { BooleanArray(n) }
        for (i in 0 until n) {
            var m = 0
            for (j in 0 until k) if (warmup[j].coverage[i] > 0f) vals[m++] = lum[j][i]
            if (m == 0) continue
            if (m < 3) {
                for (j in 0 until k) keep[j][i] = warmup[j].coverage[i] > 0f
                continue
            }
            val med = Stats.medianInPlace(vals.copyOf(m), m)
            for (q in 0 until m) dev[q] = abs(vals[q] - med)
            val sr = 1.4826f * Stats.medianInPlace(dev, m)
            val limit = max(3f * sr, 3f * floor)
            for (j in 0 until k) {
                if (warmup[j].coverage[i] <= 0f) continue
                if (abs(lum[j][i] - med) > limit) rejected[i]++ else keep[j][i] = true
            }
        }
        for ((j, p) in warmup.withIndex()) {
            for (c in 0 until channels) {
                val d = p.frame.channels[c].data
                for (i in 0 until n) {
                    if (!keep[j][i]) continue
                    val w = (p.weight * p.coverage[i]).toFloat()
                    if (w > 0f) welford(c, i, d[i], w)
                }
            }
        }
        warmup.clear()
    }

    private fun accumulate(frame: Frame, coverage: FloatArray, weight: Double, floor: Float, reject: Boolean) {
        for (c in 0 until channels) {
            val d = frame.channels[c].data
            val mc = mean[c]
            val m2c = m2[c]
            val wc = wsum[c]
            val cc = count[c]
            for (i in 0 until n) {
                val w = (weight * coverage[i]).toFloat()
                if (w <= 0f) continue
                val x = d[i]
                if (reject && cc[i] >= 5 && wc[i] > 0f) {
                    val s = max(sqrt(m2c[i] / wc[i]), floor)
                    if (abs(x - mc[i]) > kappa * s) {
                        if (c == 0) rejected[i]++
                        continue
                    }
                }
                welford(c, i, x, w)
            }
        }
    }

    private fun welford(c: Int, i: Int, x: Float, w: Float) {
        val ws = wsum[c][i] + w
        val delta = x - mean[c][i]
        mean[c][i] += (w / ws) * delta
        m2[c][i] += w * delta * (x - mean[c][i])
        wsum[c][i] = ws
        if (count[c][i] < Short.MAX_VALUE) count[c][i]++
    }

    /** Current stacked image (mean). Pixels never covered are NaN. */
    fun result(): Frame = Frame(List(channels) { c ->
        Image(width, height, FloatArray(n) { i -> if (wsum[c][i] > 0f) mean[c][i] else Float.NaN })
    })

    /** Relative coverage per pixel: weight sum / max weight sum (0..1). */
    fun coverage(): FloatArray {
        val w = wsum[0]
        val mx = w.maxOrNull()?.takeIf { it > 0 } ?: 1f
        return FloatArray(n) { w[it] / mx }
    }

    /** Snapshot of the internal state (for undo / recovery). */
    fun snapshot(): Snapshot = Snapshot(
        mean.map { it.copyOf() }, m2.map { it.copyOf() }, wsum.map { it.copyOf() }, count.map { it.copyOf() }, rejected.copyOf(), frames,
    )

    class Snapshot(
        val mean: List<FloatArray>, val m2: List<FloatArray>, val wsum: List<FloatArray>,
        val count: List<ShortArray>, val rejected: IntArray, val frames: Int,
    )

    companion object {
        const val WARMUP = 5
    }
}
