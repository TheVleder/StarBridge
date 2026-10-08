package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Master dark at working resolution, for one (camera, exposure, ISO, binning, color) key. */
class MasterDark(val frame: Frame, val exposureSec: Double, val iso: Int, val frames: Int) {
    /** Hot/cold pixels found in the dark (index list into the working image). */
    val defects: IntArray by lazy { findDefects(frame.luminance) }

    private fun findDefects(img: Image): IntArray {
        val s = Stats.sample(img, null)
        val r = Stats.clipped(s, s.size, 3f, 3)
        val hi = r.median + 5 * r.sigma.coerceAtLeast(1e-3f)
        val lo = r.median - 5 * r.sigma.coerceAtLeast(1e-3f)
        val out = ArrayList<Int>()
        for (i in img.data.indices) if (img.data[i] > hi || img.data[i] < lo) out.add(i)
        return out.toIntArray()
    }
}

/**
 * Builds a master dark from several dark frames: per-pixel mean with κσ rejection
 * (removes cosmic rays) using the same incremental algorithm as the stack.
 */
class DarkBuilder(private val exposureSec: Double, private val iso: Int) {
    private var stacker: Stacker? = null
    var count = 0
        private set

    fun add(f: Frame) {
        val s = stacker ?: Stacker(f.width, f.height, f.channels.size).also { stacker = it }
        s.add(f, FloatArray(f.width * f.height) { 1f }, 1.0)
        count++
    }

    fun build(): MasterDark? {
        val s = stacker ?: return null
        return MasterDark(s.result(), exposureSec, iso, count)
    }
}

/**
 * Hot/cold pixel map. Static defects come from the dark; dynamic ones are learnt during the
 * session: a pixel that is an isolated outlier at the same *sensor* position in most frames,
 * while the sky moves, is a defect.
 */
class DefectMap(val width: Int, val height: Int) {
    private val static = HashSet<Int>()
    private val hits = IntArray(width * height)
    private var frames = 0
    private val dynamic = HashSet<Int>()

    fun setStatic(defects: IntArray) {
        static.clear(); defects.forEach { static.add(it) }
    }

    val size get() = static.size + dynamic.size

    fun isDefect(i: Int) = i in static || i in dynamic

    val learnedFrames get() = frames

    /**
     * Learns hot pixels from [img] (σ = background noise). A defect is a **spike**: far above
     * the local background (ring 2 px away) while the *median* of its 8 neighbours stays near
     * the background (< 12 % of the excess). A real star — even perfectly tracked, so it stays
     * on the same sensor pixel, and even undersampled (FWHM 1.5 px: neighbour median ≈ 19 %)
     * — lights up most of its neighbours, so it is never taken for a defect; two adjacent hot
     * pixels or a hot pixel touching a star still are. Spikes repeating at the same sensor
     * pixel in ≥ 2 frames and > 50 % of the frames are defects (cosmic rays don't repeat).
     */
    fun learn(img: Image, sigma: Float) {
        frames++
        val w = img.width
        val h = img.height
        val s = sigma.coerceAtLeast(1e-3f)
        val ring = FloatArray(16)
        val nb = FloatArray(8)
        val quick = 4f * s
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val v = img.data[i]
            // Cheap pre-test on 4 ring samples: almost every pixel stops here.
            // At the borders the missing side is mirrored (never the pixel itself).
            val xl = if (x >= 2) x - 2 else x + 2
            val xr = if (x + 2 < w) x + 2 else x - 2
            val yu = if (y >= 2) y - 2 else y + 2
            val yd = if (y + 2 < h) y + 2 else y - 2
            val r1 = img.data[y * w + xl.coerceIn(0, w - 1)]
            val r2 = img.data[y * w + xr.coerceIn(0, w - 1)]
            val r3 = img.data[yu.coerceIn(0, h - 1) * w + x]
            val r4 = img.data[yd.coerceIn(0, h - 1) * w + x]
            if (v - maxOf(maxOf(r1, r2), maxOf(r3, r4)) < quick) continue
            var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                if (kotlin.math.abs(dx) != 2 && kotlin.math.abs(dy) != 2) continue
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                ring[n++] = img.data[yy * w + xx]
            }
            val local = Stats.medianInPlace(ring, n)
            val excess = v - local
            // Only clear spikes are learnt live: at low SNR a tiny star and a hot pixel look
            // alike. Weaker hot pixels are removed by the darks and by the stack rejection.
            if (excess < 8f * s) continue
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) nb[m++] = img.data[yy * w + xx]
            }
            if (m > 0 && Stats.medianInPlace(nb, m) - local < 0.12f * excess) hits[i]++
        }
        if (frames >= 2) {
            for (i in hits.indices) if (hits[i] >= 2 && hits[i] > frames * 0.5) dynamic.add(i)
        }
    }

    /** Replaces every defect by the median of its non-defective 8 neighbours (all channels). */
    fun correct(frame: Frame) {
        val all = static + dynamic
        if (all.isEmpty()) return
        val nb = FloatArray(8)
        for (c in frame.channels) {
            val d = c.data
            for (i in all) {
                val x = i % width
                val y = i / width
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx
                    val yy = y + dy
                    if (xx !in 0 until width || yy !in 0 until height) continue
                    val j = yy * width + xx
                    if (j in all) continue
                    nb[n++] = d[j]
                }
                if (n > 0) d[i] = Stats.medianInPlace(nb, n)
            }
        }
    }
}

object Calibration {
    /**
     * Subtracts the dark scaled by k that best removes the hot pixels (phones have no
     * cooled sensors, so dark current drifts with temperature). Returns k.
     */
    fun subtractDark(frame: Frame, dark: MasterDark): Double {
        val lum = frame.luminance
        val dl = dark.frame.luminance
        var num = 0.0
        var den = 0.0
        val med = Stats.median(Stats.sample(lum, null, 50_000))
        val dmed = Stats.median(Stats.sample(dl, null, 50_000))
        for (i in dark.defects) {
            if (dl.data[i] <= dmed) continue
            val l = (lum.data[i] - med).toDouble()
            val d = (dl.data[i] - dmed).toDouble()
            num += l * d; den += d * d
        }
        val k = if (den > 0) (num / den).coerceIn(0.5, 1.5) else 1.0
        for (c in frame.channels.indices) {
            val a = frame.channels[c].data
            val b = dark.frame.channels[c].data
            for (i in a.indices) a[i] -= (k * b[i]).toFloat()
        }
        return k
    }

    /**
     * Detects the eyepiece circle (afocal photography): edge points of the illuminated disc
     * along radial lines, then a RANSAC circle fit. Null if the frame has no such circle.
     */
    fun eyepieceMask(img: Image): Mask? {
        val w = img.width
        val h = img.height
        val cx0 = w / 2.0
        val cy0 = h / 2.0
        val bgInside = Stats.median(Stats.sample(img, null, 50_000))
        val corners = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1).map { (x, y) ->
            val vals = ArrayList<Float>()
            for (dy in 0 until minOf(16, h)) for (dx in 0 until minOf(16, w)) {
                val xx = if (x == 0) dx else x - dx
                val yy = if (y == 0) dy else y - dy
                vals.add(img[xx, yy])
            }
            Stats.median(vals.toFloatArray())
        }
        val dark = Stats.median(corners.toFloatArray())
        // No vignetting: the corners are as bright as the middle.
        if (bgInside - dark < 0.25f * abs(bgInside) || bgInside - dark < 1f) return null
        val thr = dark + 0.5f * (bgInside - dark)
        val pts = ArrayList<DoubleArray>()
        val rays = 180
        val maxR = hypot(cx0, cy0)
        for (k in 0 until rays) {
            val ang = 2 * Math.PI * k / rays
            var lastInside = -1.0
            var r = 5.0
            while (r < maxR) {
                val x = (cx0 + r * cos(ang)).toInt()
                val y = (cy0 + r * sin(ang)).toInt()
                if (x !in 0 until w || y !in 0 until h) break
                // Median of a small neighbourhood avoids stopping on a dark gap between stars.
                if (img[x, y] > thr) lastInside = r
                r += 1.0
            }
            if (lastInside > 10) pts.add(doubleArrayOf(cx0 + lastInside * cos(ang), cy0 + lastInside * sin(ang)))
        }
        if (pts.size < 30) return null
        val rnd = Random(42)
        var best: DoubleArray? = null
        var bestIn = 0
        repeat(400) {
            val a = pts[rnd.nextInt(pts.size)]; val b = pts[rnd.nextInt(pts.size)]; val c = pts[rnd.nextInt(pts.size)]
            val circ = circleFrom3(a, b, c) ?: return@repeat
            val inl = pts.count { abs(hypot(it[0] - circ[0], it[1] - circ[1]) - circ[2]) < 2.0 }
            if (inl > bestIn) { bestIn = inl; best = circ }
        }
        val c = best ?: return null
        if (bestIn < pts.size * 0.6) return null
        val radius = c[2] * 0.97
        val mask = Mask.none(w, h)
        for (y in 0 until h) for (x in 0 until w) if (hypot(x - c[0], y - c[1]) < radius) mask.data[y * w + x] = 1
        return mask
    }

    private fun circleFrom3(a: DoubleArray, b: DoubleArray, c: DoubleArray): DoubleArray? {
        val d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]))
        if (abs(d) < 1e-6) return null
        val a2 = a[0] * a[0] + a[1] * a[1]
        val b2 = b[0] * b[0] + b[1] * b[1]
        val c2 = c[0] * c[0] + c[1] * c[1]
        val ux = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d
        val uy = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d
        return doubleArrayOf(ux, uy, hypot(a[0] - ux, a[1] - uy))
    }

    /** Border mask (no eyepiece circle): everything except a margin. */
    fun borderMask(w: Int, h: Int, margin: Int = 16): Mask {
        val m = Mask.none(w, h)
        for (y in margin until h - margin) for (x in margin until w - margin) m.data[y * w + x] = 1
        return m
    }

}
