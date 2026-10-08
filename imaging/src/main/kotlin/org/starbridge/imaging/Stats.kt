package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.sqrt

/** Robust statistics used everywhere (median, MAD, sigma clipping). */
object Stats {
    /** Median of the first [n] values (the array is partially reordered). */
    fun medianInPlace(a: FloatArray, n: Int = a.size): Float {
        if (n == 0) return Float.NaN
        val k = n / 2
        val m = select(a, 0, n - 1, k)
        if (n % 2 == 1) return m
        // Even count: the lower middle is the max of the left part.
        var lo = a[0]
        for (i in 1 until k) if (a[i] > lo) lo = a[i]
        return (lo + m) / 2f
    }

    fun median(values: FloatArray): Float = medianInPlace(values.copyOf())

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    fun median(values: List<Double>): Double = median(values.toDoubleArray())

    /** Normalized MAD (≈ σ for Gaussian noise) of a list. */
    fun madSigma(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = median(values)
        return 1.4826 * median(values.map { abs(it - m) })
    }

    /** Hoare quickselect: returns the k-th smallest of a[lo..hi]. */
    private fun select(a: FloatArray, loIn: Int, hiIn: Int, k: Int): Float {
        var lo = loIn
        var hi = hiIn
        while (lo < hi) {
            val pivot = a[(lo + hi) ushr 1]
            var i = lo
            var j = hi
            while (i <= j) {
                while (a[i] < pivot) i++
                while (a[j] > pivot) j--
                if (i <= j) {
                    val t = a[i]; a[i] = a[j]; a[j] = t
                    i++; j--
                }
            }
            if (k <= j) hi = j else if (k >= i) lo = i else return a[k]
        }
        return a[k]
    }

    data class Robust(val median: Float, val sigma: Float)

    /**
     * Sigma-clipped median and σ (MAD based) of [values] (first [n] entries); the array is
     * reordered. Used for tile backgrounds and noise.
     */
    fun clipped(values: FloatArray, nIn: Int = values.size, kappa: Float = 2.5f, iterations: Int = 3): Robust {
        var n = nIn
        if (n == 0) return Robust(Float.NaN, Float.NaN)
        var med = 0f
        var sigma = 0f
        val dev = FloatArray(n)
        repeat(iterations) {
            med = medianInPlace(values, n)
            for (i in 0 until n) dev[i] = abs(values[i] - med)
            sigma = 1.4826f * medianInPlace(dev, n)
            if (sigma <= 0f) return Robust(med, 0f)
            var w = 0
            for (i in 0 until n) if (abs(values[i] - med) <= kappa * sigma) values[w++] = values[i]
            if (w == n || w < 8) return Robust(med, sigma)
            n = w
        }
        return Robust(medianInPlace(values, n), sigma)
    }

    /** Sample up to [maxSamples] pixels of [img] inside [mask] (deterministic stride). */
    fun sample(img: Image, mask: Mask?, maxSamples: Int = 200_000): FloatArray {
        val total = img.data.size
        val step = maxOf(1, total / maxSamples)
        val out = FloatArray(total / step + 1)
        var n = 0
        var i = 0
        while (i < total) {
            val v = img.data[i]
            if ((mask == null || mask[i]) && !v.isNaN()) out[n++] = v
            i += step
        }
        return out.copyOf(n)
    }

    /**
     * Noise σ insensitive to stars, nebulosity, gradients (vignetting edge, light pollution)
     * and the smoothing of bilinear resampling: the image is binned 2×2 (averages), then the
     * **second differences** a[i−1] − 2a[i] + a[i+1] (zero for any linear slope) give
     * σ = 1.4826·MAD/√6, rescaled ×2 back to single-pixel noise. NaN/masked pixels skipped.
     */
    fun robustNoise(img: Image, mask: Mask?): Double {
        val bw = img.width / 2
        val bh = img.height / 2
        val bin = FloatArray(bw * bh) { Float.NaN }
        for (y in 0 until bh) for (x in 0 until bw) {
            val i0 = (2 * y) * img.width + 2 * x
            val idx = intArrayOf(i0, i0 + 1, i0 + img.width, i0 + img.width + 1)
            if (mask != null && idx.any { !mask[it] }) continue
            val s = idx.sumOf { img.data[it].toDouble() }
            if (!s.isNaN()) bin[y * bw + x] = (s / 4).toFloat()
        }
        val d = FloatArray(bw * bh)
        var n = 0
        for (y in 0 until bh) for (x in 1 until bw - 1) {
            val a = bin[y * bw + x - 1]
            val b = bin[y * bw + x]
            val c = bin[y * bw + x + 1]
            if (!a.isNaN() && !b.isNaN() && !c.isNaN()) d[n++] = kotlin.math.abs(a - 2 * b + c)
        }
        if (n < 10) return Double.NaN
        return 1.4826 * medianInPlace(d, n) / sqrt(6.0) * 2.0
    }

    fun rms(values: List<Double>): Double = if (values.isEmpty()) 0.0 else sqrt(values.sumOf { it * it } / values.size)
}
