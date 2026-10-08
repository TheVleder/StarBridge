package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

enum class DenoiseLevel(val strength: Double?) { AUTO(null), OFF(0.0), LOW(0.6), MEDIUM(1.0), HIGH(1.6) }

data class RenderSettings(
    /** Median of the stretched image (auto stretch target). */
    val targetBackground: Double = 0.25,
    /** Manual adjustments on top of the auto stretch: black point shift and contrast. */
    val blackShift: Double = 0.0,
    val contrast: Double = 1.0,
    val removeGradient: Boolean = true,
    val denoise: DenoiseLevel = DenoiseLevel.AUTO,
    val nightRed: Boolean = false,
    val maxSize: Int = 1600,
)

/** An 8-bit ARGB picture ready to encode (JPEG on the phone). */
class Picture(val width: Int, val height: Int, val argb: IntArray)

/**
 * Display post-processing (never modifies the stack data): crop to the covered area,
 * gradient removal, colour neutralization, auto-stretch (STF), wavelet denoise, night mode.
 */
object Renderer {
    fun render(stack: Frame, coverage: FloatArray?, mask: Mask?, s: RenderSettings, framesStacked: Int): Picture {
        // 1. Crop to well-covered pixels and downscale for the preview.
        var f = crop(stack, coverage, mask)
        val scale = max(f.width, f.height).toDouble() / s.maxSize
        if (scale > 1.0) f = downscale(f, kotlin.math.ceil(scale).toInt())
        val w = f.width
        val h = f.height
        val ch = f.channels.map { it.data.copyOf() }
        for (c in ch) for (i in c.indices) if (c[i].isNaN()) c[i] = 0f
        // 2. Gradient (light pollution, moon, vignetting).
        if (s.removeGradient) for (c in ch) removeGradient(c, w, h)
        // 3. Colour: background neutralization.
        if (ch.size == 3) neutralize(ch, w, h)
        // 4. Normalize to 0..1 with robust black and white points.
        val lum = if (ch.size == 3) FloatArray(w * h) { (ch[0][it] + ch[1][it] + ch[2][it]) / 3f } else ch[0]
        val sample = Stats.sample(Image(w, h, lum), null, 100_000)
        val lo = percentile(sample, 0.001)
        val hi = max(lo + 1e-6f, percentile(sample, 0.9995))
        for (c in ch) for (i in c.indices) c[i] = ((c[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
        // 5. Wavelet denoise (strength falls as the stack gets deeper).
        val k = s.denoise.strength ?: (1.5 - 0.25 * log2(framesStacked.coerceAtLeast(1).toDouble())).coerceIn(0.0, 1.5)
        if (k > 0.05) for (c in ch) denoise(c, w, h, k)
        // 6. Auto stretch (STF midtones transfer), linked across channels.
        val l2 = if (ch.size == 3) FloatArray(w * h) { (ch[0][it] + ch[1][it] + ch[2][it]) / 3f } else ch[0]
        val st = Stats.sample(Image(w, h, l2), null, 100_000)
        val med = Stats.medianInPlace(st.copyOf())
        val dev = FloatArray(st.size) { abs(st[it] - med) }
        val madn = 1.4826f * Stats.medianInPlace(dev)
        val c0 = (med - 2.8f * madn + s.blackShift.toFloat() * 0.05f).coerceIn(0f, 0.99f)
        val x0 = ((med - c0) / (1 - c0)).coerceIn(1e-4f, 0.999f).toDouble()
        val t = (s.targetBackground / s.contrast).coerceIn(0.05, 0.6)
        val m = (x0 * (t - 1) / (2 * t * x0 - t - x0)).coerceIn(1e-4, 0.9999)
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val rgb = IntArray(3)
            for (c in 0 until 3) {
                val v = ch[if (ch.size == 3) c else 0][i]
                val x = ((v - c0) / (1 - c0)).coerceIn(0f, 1f).toDouble()
                val y = if (x <= 0) 0.0 else if (x >= 1) 1.0 else ((m - 1) * x) / ((2 * m - 1) * x - m)
                rgb[c] = (y * 255).roundToInt().coerceIn(0, 255)
            }
            out[i] = if (s.nightRed) {
                val l = (rgb[0] + rgb[1] + rgb[2]) / 3
                (0xFF shl 24) or (l shl 16)
            } else (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        return Picture(w, h, out)
    }

    private fun log2(x: Double) = kotlin.math.ln(x) / kotlin.math.ln(2.0)

    private fun percentile(a: FloatArray, p: Double): Float {
        if (a.isEmpty()) return 0f
        val s = a.copyOf()
        s.sort()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    fun crop(stack: Frame, coverage: FloatArray?, mask: Mask?): Frame {
        val w = stack.width
        val h = stack.height
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val ok = (coverage == null || coverage[i] >= 0.5f) && (mask == null || mask[i]) && !stack.channels[0].data[i].isNaN()
            if (ok) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y) }
        }
        if (x1 < x0 || y1 < y0) return stack
        val cw = x1 - x0 + 1
        val chh = y1 - y0 + 1
        return Frame(stack.channels.map { img ->
            Image(cw, chh, FloatArray(cw * chh) { k ->
                val x = x0 + k % cw
                val y = y0 + k / cw
                val i = y * w + x
                val ok = (coverage == null || coverage[i] >= 0.5f) && (mask == null || mask[i])
                if (ok) img.data[i] else Float.NaN
            })
        })
    }

    fun downscale(f: Frame, k: Int): Frame {
        val w = f.width / k
        val h = f.height / k
        return Frame(f.channels.map { img ->
            Image(w, h, FloatArray(w * h) { o ->
                val ox = o % w
                val oy = o / w
                var s = 0f
                var n = 0
                for (dy in 0 until k) for (dx in 0 until k) {
                    val v = img.data[(oy * k + dy) * img.width + ox * k + dx]
                    if (!v.isNaN()) { s += v; n++ }
                }
                if (n > 0) s / n else Float.NaN
            })
        })
    }

    /** Fits a robust quadratic surface to star-free background boxes and subtracts it. */
    fun removeGradient(d: FloatArray, w: Int, h: Int) {
        val gx = 16
        val gy = 16
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val vs = ArrayList<Double>()
        val buf = FloatArray((w / gx + 1) * (h / gy + 1))
        for (ty in 0 until gy) for (tx in 0 until gx) {
            var n = 0
            for (y in ty * h / gy until (ty + 1) * h / gy) for (x in tx * w / gx until (tx + 1) * w / gx) {
                val v = d[y * w + x]
                if (v != 0f && n < buf.size) buf[n++] = v
            }
            if (n < 10) continue
            val r = Stats.clipped(buf, n, 2.5f, 3)
            xs.add((tx + 0.5) / gx - 0.5); ys.add((ty + 0.5) / gy - 0.5); vs.add(r.median.toDouble())
        }
        if (vs.size < 12) return
        // Skip boxes dominated by bright nebulosity: > median + 3 MAD of box values.
        val med = Stats.median(vs)
        val mad = Stats.madSigma(vs).coerceAtLeast(1e-9)
        val keep = vs.indices.filter { vs[it] < med + 3 * mad }
        var wts = DoubleArray(keep.size) { 1.0 }
        var coef = DoubleArray(6)
        repeat(4) {
            coef = solvePoly(keep.map { xs[it] }, keep.map { ys[it] }, keep.map { vs[it] }, wts) ?: return
            // Huber reweighting.
            val res = keep.map { j -> vs[j] - poly(coef, xs[j], ys[j]) }
            val s = 1.4826 * Stats.median(res.map { abs(it) }).coerceAtLeast(1e-9)
            wts = DoubleArray(keep.size) { i -> val r = abs(res[i]) / (1.5 * s); if (r <= 1) 1.0 else 1 / r }
        }
        val pedestal = med
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0f) continue
            d[i] = (d[i] - poly(coef, x.toDouble() / w - 0.5, y.toDouble() / h - 0.5) + pedestal).toFloat()
        }
    }

    private fun poly(c: DoubleArray, x: Double, y: Double) = c[0] + c[1] * x + c[2] * y + c[3] * x * x + c[4] * x * y + c[5] * y * y

    private fun solvePoly(x: List<Double>, y: List<Double>, v: List<Double>, w: DoubleArray): DoubleArray? {
        val n = 6
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (k in x.indices) {
            val t = doubleArrayOf(1.0, x[k], y[k], x[k] * x[k], x[k] * y[k], y[k] * y[k])
            for (i in 0 until n) {
                b[i] += w[k] * t[i] * v[k]
                for (j in 0 until n) a[i][j] += w[k] * t[i] * t[j]
            }
        }
        return Linear.solve(a, b)
    }

    /** Equalizes the background median of each channel (neutral grey sky). */
    private fun neutralize(ch: List<FloatArray>, w: Int, h: Int) {
        val meds = ch.map { c -> Stats.clipped(Stats.sample(Image(w, h, c), null, 50_000)).median }
        val target = meds.average().toFloat()
        for (k in ch.indices) {
            val off = target - meds[k]
            val c = ch[k]
            for (i in c.indices) if (c[i] != 0f) c[i] += off
        }
    }

    /**
     * À-trous (B3-spline) wavelet denoise on the 2 finest scales, soft thresholding at
     * k·σ, protecting bright structures (stars, bright nebula) with a mask.
     */
    fun denoise(d: FloatArray, w: Int, h: Int, k: Double) {
        val kernel = floatArrayOf(1f / 16, 4f / 16, 6f / 16, 4f / 16, 1f / 16)
        var current = d.copyOf()
        val details = ArrayList<FloatArray>()
        for (scale in 0 until 2) {
            val step = 1 shl scale
            val smooth = convolve(current, w, h, kernel, step)
            details.add(FloatArray(d.size) { current[it] - smooth[it] })
            current = smooth
        }
        // Noise from the finest scale (MAD), with B3 scale factors 0.889 and 0.200.
        val s0 = Stats.clipped(Stats.sample(Image(w, h, details[0]), null, 100_000)).sigma / 0.889f
        val factors = floatArrayOf(0.889f, 0.200f)
        val protect = Stats.median(Stats.sample(Image(w, h, d), null, 50_000)) + 6 * s0
        for (j in details.indices) {
            val tau = (k * s0 * factors[j]).toFloat()
            val dj = details[j]
            for (i in dj.indices) {
                if (d[i] > protect) continue
                val v = dj[i]
                dj[i] = if (abs(v) <= tau) 0f else v - tau * sign(v)
            }
        }
        for (i in d.indices) d[i] = current[i] + details[0][i] + details[1][i]
    }

    private fun convolve(src: FloatArray, w: Int, h: Int, k: FloatArray, step: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (j in -2..2) {
                val xx = (x + j * step).coerceIn(0, w - 1)
                a += src[y * w + xx] * k[j + 2]
            }
            tmp[y * w + x] = a
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (j in -2..2) {
                val yy = (y + j * step).coerceIn(0, h - 1)
                a += tmp[yy * w + x] * k[j + 2]
            }
            out[y * w + x] = a
        }
        return out
    }
}

/** Small dense linear solver (Gaussian elimination with partial pivoting). */
object Linear {
    fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray? {
        val n = bIn.size
        val m = Array(n) { i -> aIn[i].copyOf() + bIn[i] }
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-14) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = m[r][n]
            for (k in r + 1 until n) s -= m[r][k] * x[k]
            x[r] = s / m[r][r]
        }
        return x
    }
}
