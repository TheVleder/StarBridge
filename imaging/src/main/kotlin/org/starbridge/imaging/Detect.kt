package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Star(
    val x: Double,
    val y: Double,
    /** Background-subtracted flux in the aperture (ADU). */
    val flux: Double,
    val peak: Double,
    val snr: Double,
    val fwhm: Double,
    /** Half-flux radius (px): robust for out-of-focus "donut" stars. */
    val hfr: Double,
    /** sqrt(λ1/λ2) of the second moments: 1 = round. */
    val elongation: Double,
    val saturated: Boolean,
)

data class DetectionStats(
    val count: Int,
    val medianFwhm: Double,
    val medianHfr: Double,
    val medianElongation: Double,
    val saturatedFraction: Double,
)

class Detection(val stars: List<Star>, val all: List<Star>, val stats: DetectionStats, val background: Background)

/**
 * Star detection: matched filter (Gaussian), local maxima above k·σ, then sub-pixel
 * centroids, flux, FWHM, HFR and elongation for the brightest candidates.
 */
class StarDetector(
    private val kSigma: Double = 5.0,
    private val maxCandidates: Int = 400,
    private val maxStars: Int = 150,
    private val edge: Int = 8,
) {
    fun detect(img: Image, mask: Mask?, unsaturated: Mask?, fwhmGuess: Double = 3.0, bgIn: Background? = null): Detection {
        val bg = bgIn ?: Background.estimate(img, mask)
        val w = img.width
        val h = img.height
        // Background-subtracted image.
        val sub = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) sub[y * w + x] = img.data[y * w + x] - bg.at(x, y)
        // Matched filter: separable Gaussian with σ = FWHM/2.355.
        val sigmaF = (fwhmGuess / 2.355).coerceIn(0.6, 4.0)
        val filtered = gaussianBlur(sub, w, h, sigmaF)
        val noiseF = filteredNoise(filtered, w, h, mask)
        val threshold = (kSigma * noiseF).toFloat()

        // Local maxima (5×5) above threshold.
        val cands = ArrayList<IntArray>()
        for (y in edge until h - edge) for (x in edge until w - edge) {
            val i = y * w + x
            val v = filtered[i]
            if (v <= threshold) continue
            if (mask != null && !mask[i]) continue
            var isMax = true
            loop@ for (dy in -2..2) for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue
                val u = filtered[i + dy * w + dx]
                if (u > v || (u == v && (dy < 0 || (dy == 0 && dx < 0)))) { isMax = false; break@loop }
            }
            if (isMax) cands.add(intArrayOf(x, y, java.lang.Float.floatToRawIntBits(v)))
        }
        cands.sortByDescending { java.lang.Float.intBitsToFloat(it[2]) }
        val top = cands.take(maxCandidates)

        val stars = ArrayList<Star>()
        for (c in top) {
            val s = measure(img, sub, bg.sigma.toDouble(), c[0], c[1], fwhmGuess, unsaturated, mask) ?: continue
            stars.add(s)
        }
        // Sharpness = peak / flux: a hot pixel or cosmic ray puts almost all its light in one
        // pixel (> 0.6), a real star (FWHM ≥ ~1.5 px) spreads it (< 0.4). Never a "star".
        val good = stars.filter {
            !it.saturated && it.fwhm >= 1.0 && it.elongation <= 2.5 && it.snr >= 5 && it.peak / it.flux < MAX_SHARPNESS
        }
        val balanced = balance(good, w, h)
        val med = { f: (Star) -> Double -> if (good.isEmpty()) Double.NaN else Stats.median(good.map(f)) }
        // Shape statistics include elongated detections: if every star is a streak, the frame
        // must look streaked (registration uses only the round ones).
        val shaped = stars.filter { !it.saturated && it.snr >= 5 && it.peak / it.flux < MAX_SHARPNESS }
        val stats = DetectionStats(
            count = good.size,
            medianFwhm = med { it.fwhm },
            medianHfr = med { it.hfr },
            medianElongation = if (shaped.isEmpty()) Double.NaN else Stats.median(shaped.map { it.elongation }),
            saturatedFraction = if (stars.isEmpty()) 0.0 else stars.count { it.saturated }.toDouble() / stars.size,
        )
        return Detection(balanced, stars, stats, bg)
    }

    /** Brightest stars spread over a 6×6 grid so registration sees the whole field. */
    private fun balance(stars: List<Star>, w: Int, h: Int): List<Star> {
        val cells = HashMap<Int, MutableList<Star>>()
        for (s in stars.sortedByDescending { it.flux }) {
            val key = (s.y * 6 / h).toInt() * 6 + (s.x * 6 / w).toInt()
            cells.getOrPut(key) { ArrayList() }.add(s)
        }
        val out = ArrayList<Star>()
        var round = 0
        while (out.size < maxStars) {
            var added = false
            for (list in cells.values) if (round < list.size && out.size < maxStars) { out.add(list[round]); added = true }
            if (!added) break
            round++
        }
        return out.sortedByDescending { it.flux }
    }

    /** Sub-pixel measurement around (cx, cy); null if not a usable star. */
    private fun measure(img: Image, sub: FloatArray, sigma: Double, cx: Int, cy: Int, fwhmGuess: Double, unsat: Mask?, mask: Mask?): Star? {
        val w = img.width
        val h = img.height
        val r = max(3, (2.0 * max(fwhmGuess, 2.0)).toInt()).coerceAtMost(15)
        if (cx - r - 3 < 0 || cy - r - 3 < 0 || cx + r + 3 >= w || cy + r + 3 >= h) return null
        // Iterative Gaussian-weighted centroid.
        var x = cx.toDouble()
        var y = cy.toDouble()
        val sw = max(fwhmGuess, 1.5) / 2.355 * 1.5
        for (iter in 0 until 6) {
            var sx = 0.0; var sy = 0.0; var st = 0.0
            for (yy in cy - r..cy + r) for (xx in cx - r..cx + r) {
                val v = sub[yy * w + xx].toDouble()
                if (v <= 0) continue
                val d2 = (xx - x) * (xx - x) + (yy - y) * (yy - y)
                val wgt = v * exp(-d2 / (2 * sw * sw))
                sx += wgt * xx; sy += wgt * yy; st += wgt
            }
            if (st <= 0) return null
            val nx = sx / st
            val ny = sy / st
            val moved = hypot(nx - x, ny - y)
            x = nx; y = ny
            if (moved < 0.005) break
        }
        if (abs(x - cx) > r || abs(y - cy) > r) return null
        // Size and shape by **elliptical adaptive moments**: the Gaussian weight W has the
        // covariance C of the current estimate. For a Gaussian star of covariance Σ the
        // weighted covariance is M = (Σ⁻¹ + C⁻¹)⁻¹, so Σ = (M⁻¹ − C⁻¹)⁻¹; iterating C ← Σ
        // converges to the true shape, independent of the guess, for round stars and streaks.
        val s0 = (max(fwhmGuess, 1.5) / 2.355).let { it * it }
        var c11 = s0; var c22 = s0; var c12 = 0.0
        var cxx = s0; var cyy = s0; var cxy = 0.0
        val win = (r + 2).coerceAtMost(minOf(cx, cy, w - 1 - cx, h - 1 - cy))
        var converged = false
        for (iter in 0 until 12) {
            val det = c11 * c22 - c12 * c12
            if (det <= 1e-9) return null
            val i11 = c22 / det; val i22 = c11 / det; val i12 = -c12 / det
            var mxx = 0.0; var myy = 0.0; var mxy = 0.0; var mw = 0.0
            for (yy in cy - win..cy + win) for (xx in cx - win..cx + win) {
                val dx = xx - x
                val dy = yy - y
                val q = i11 * dx * dx + 2 * i12 * dx * dy + i22 * dy * dy
                if (q > 16) continue
                val v = sub[yy * w + xx].toDouble()
                val g = exp(-0.5 * q)
                mxx += v * g * dx * dx; myy += v * g * dy * dy; mxy += v * g * dx * dy; mw += v * g
            }
            if (mw <= 0) return null
            mxx /= mw; myy /= mw; mxy /= mw
            // Σ = (M⁻¹ − C⁻¹)⁻¹
            val dm = mxx * myy - mxy * mxy
            if (dm <= 1e-9) return null
            val a11 = myy / dm - i11
            val a22 = mxx / dm - i22
            val a12 = -mxy / dm - i12
            val da = a11 * a22 - a12 * a12
            if (a11 <= 0 || a22 <= 0 || da <= 1e-12) return null // not a compact source
            cxx = a22 / da; cyy = a11 / da; cxy = -a12 / da
            val change = abs(cxx - c11) + abs(cyy - c22) + abs(cxy - c12)
            c11 = cxx.coerceIn(0.1, 400.0); c22 = cyy.coerceIn(0.1, 400.0); c12 = cxy
            if (change < 0.002 * (c11 + c22)) { converged = true; break }
        }
        if (!converged && (cxx + cyy) > 2 * win * win) return null
        val tr = cxx + cyy
        val det = cxx * cyy - cxy * cxy
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc
        val l2 = max(1e-6, tr / 2 - disc)
        val fwhm = 2.355 * sqrt(max(1e-6, (l1 + l2) / 2))
        val elong = sqrt(l1 / l2)
        // Flux, peak and half-flux radius in an aperture scaled to the measured size.
        val ap = (2.5 * fwhm).coerceIn(3.0, r.toDouble())
        var flux = 0.0; var peak = 0.0
        var nPix = 0
        var saturated = false
        val radial = ArrayList<DoubleArray>()
        for (yy in cy - r..cy + r) for (xx in cx - r..cx + r) {
            val d = hypot(xx - x, yy - y)
            if (d > ap) continue
            val i = yy * w + xx
            if (mask != null && !mask[i]) return null
            if (unsat != null && !unsat[i] && d < ap * 0.6) saturated = true
            val v = sub[i].toDouble()
            nPix++
            flux += v
            if (v > peak) peak = v
            radial.add(doubleArrayOf(d, v))
        }
        if (flux <= 0) return null
        // Half flux radius from the cumulative radial profile.
        radial.sortBy { it[0] }
        var cum = 0.0
        var hfr = ap
        for (p in radial) {
            cum += p[1]
            if (cum >= flux / 2) { hfr = p[0]; break }
        }
        // Background-limited SNR (the gain is not known here): enough to rank and filter stars.
        val snr = flux / (max(1e-9, sigma) * sqrt(nPix.toDouble()))
        return Star(x, y, flux, peak, snr, fwhm, max(0.3, hfr), elong, saturated)
    }

    companion object {
        const val MAX_SHARPNESS = 0.6

        fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
            val r = max(1, (3 * sigma).toInt())
            val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)).toFloat() }
            val sum = k.sum()
            for (i in k.indices) k[i] /= sum
            val tmp = FloatArray(w * h)
            val out = FloatArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var acc = 0f
                    for (j in -r..r) {
                        val xx = min(w - 1, max(0, x + j))
                        acc += src[row + xx] * k[j + r]
                    }
                    tmp[row + x] = acc
                }
            }
            for (y in 0 until h) for (x in 0 until w) {
                var acc = 0f
                for (j in -r..r) {
                    val yy = min(h - 1, max(0, y + j))
                    acc += tmp[yy * w + x] * k[j + r]
                }
                out[y * w + x] = acc
            }
            return out
        }

        private fun filteredNoise(f: FloatArray, w: Int, h: Int, mask: Mask?): Double {
            val step = maxOf(1, f.size / 150_000)
            val s = FloatArray(f.size / step + 1)
            var n = 0
            var i = 0
            while (i < f.size) {
                if (mask == null || mask[i]) s[n++] = f[i]
                i += step
            }
            return Stats.clipped(s, n, 3f, 3).sigma.toDouble().coerceAtLeast(1e-6)
        }
    }
}
