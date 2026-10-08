package org.starbridge.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Synthetic sky for tests and the demo camera: realistic stars (Moffat PSF), a faint galaxy
 * and nebula, sky gradient, eyepiece vignetting, sensor noise, fixed hot pixels, cosmic rays,
 * satellites and every kind of bad frame. Everything is deterministic from the seed.
 */
class SkySimulator(
    val width: Int = 512,
    val height: Int = 512,
    val seed: Int = 7,
    val starCount: Int = 220,
    /** Sky background electrons per pixel per second. */
    val skyRate: Double = 40.0,
    val readNoise: Double = 3.0,
    /** e⁻ per ADU. */
    val gain: Double = 1.0,
    val darkCurrent: Double = 0.5,
    val hotPixels: Int = 60,
    val whiteLevel: Int = 16383,
    val blackLevel: Int = 64,
    val cfa: Cfa = Cfa.MONO,
    /** Afocal eyepiece circle (radius as a fraction of min(w, h)/2), null = full frame. */
    val eyepieceRadius: Double? = null,
    /** Faint extended objects (surface brightness in e⁻/s/px at the centre). */
    val galaxyRate: Double = 0.0,
    val nebulaRate: Double = 0.0,
) {
    data class SimStar(val x: Double, val y: Double, val rate: Double)

    val stars: List<SimStar>
    private val hot: List<Triple<Int, Int, Double>>
    val galaxyX = width * 0.55
    val galaxyY = height * 0.45

    init {
        val r = Random(seed)
        // Luminosity function: many faint, few bright (power law).
        stars = List(starCount) {
            val u = r.nextDouble()
            val rate = 30.0 * 10.0.pow(3.0 * (1 - u).pow(2.5)) // 30 … 30,000 e⁻/s
            SimStar(r.nextDouble() * (width - 1), r.nextDouble() * (height - 1), rate)
        }
        hot = List(hotPixels) { Triple(r.nextInt(width), r.nextInt(height), 200.0 + r.nextDouble() * 3000.0) }
    }

    /** Settings of one simulated exposure. */
    data class Shot(
        val exposureSec: Double = 10.0,
        /** Frame → sky transform (where the frame looks): rotation about the centre + drift. */
        val transform: Similarity = Similarity.IDENTITY,
        val fwhm: Double = 3.0,
        val transparency: Double = 1.0,
        /** Elongation of stars (wind/trailing), 1 = round. */
        val elongation: Double = 1.0,
        val elongationAngleDeg: Double = 30.0,
        /** Out of focus: stars become donuts with this outer radius (px), 0 = in focus. */
        val donutRadius: Double = 0.0,
        val satellite: Boolean = false,
        val cosmicRays: Int = 0,
        /** Patchy opaque cloud covering this fraction of the field. */
        val cloudCover: Double = 0.0,
        val gradient: Double = 0.3,
        val noiseSeed: Int = 1,
        val timestampMs: Long = 0,
        val gyroRms: Double = 0.01,
        /** True to render without any noise (ground truth). */
        val noiseFree: Boolean = false,
        /** Sensor temperature factor for dark current and hot pixels. */
        val thermal: Double = 1.0,
        /** Covered telescope (dark frame). */
        val dark: Boolean = false,
        /** Reported in the frame metadata (the gain is a constructor parameter). */
        val iso: Int = 800,
    )

    /** Expected noise-free signal (e⁻/s) of the sky + objects at sky position (sx, sy), without stars. */
    private fun extendedRate(sx: Double, sy: Double): Double {
        var v = 0.0
        if (galaxyRate > 0) {
            // Sérsic-like elliptical galaxy (n = 1 exponential disk).
            val dx = sx - galaxyX
            val dy = sy - galaxyY
            val c = cos(0.6); val s = sin(0.6)
            val u = dx * c + dy * s
            val w = -dx * s + dy * c
            val r = sqrt(u * u + (w / 0.45) * (w / 0.45))
            v += galaxyRate * exp(-r / 14.0)
        }
        if (nebulaRate > 0) {
            val dx = sx - width * 0.3
            val dy = sy - height * 0.65
            v += nebulaRate * exp(-(dx * dx + dy * dy) / (2 * 30.0 * 30.0)) * (1 + 0.6 * sin(dx / 7.0) * cos(dy / 9.0))
        }
        return v
    }

    /** Renders a raw frame with the given shot settings. */
    fun render(shot: Shot): RawFrame {
        val rnd = Random(seed * 7919 + shot.noiseSeed)
        val t = shot.exposureSec
        val img = DoubleArray(width * height)
        val cx = width / 2.0
        val cy = height / 2.0
        val inv = shot.transform // frame → sky
        // Sky background with gradient and extended objects.
        if (!shot.dark) {
            for (y in 0 until height) for (x in 0 until width) {
                val sx = inv.applyX(x.toDouble(), y.toDouble())
                val sy = inv.applyY(x.toDouble(), y.toDouble())
                val grad = 1.0 + shot.gradient * (x / width.toDouble() - 0.5) + 0.5 * shot.gradient * (y / height.toDouble() - 0.5)
                img[y * width + x] = (skyRate * grad + extendedRate(sx, sy) * shot.transparency) * t
            }
            // Stars, placed through the inverse transform (sky → frame).
            val toFrame = inv.inverse()
            for (s in stars) {
                val fx = toFrame.applyX(s.x, s.y)
                val fy = toFrame.applyY(s.x, s.y)
                if (fx < -20 || fy < -20 || fx > width + 20 || fy > height + 20) continue
                var cloud = 1.0
                if (shot.cloudCover > 0) cloud = cloudFactor(fx, fy, shot)
                addStar(img, fx, fy, s.rate * t * shot.transparency * cloud, shot)
            }
            if (shot.satellite) {
                val y0 = height * 0.2
                for (x in 0 until width) {
                    val y = y0 + x * 0.4
                    val yi = y.roundToInt()
                    for (dy in -1..1) if (yi + dy in 0 until height) img[(yi + dy) * width + x] += 4000.0 * exp(-(dy * dy) / 1.0)
                }
            }
        }
        // Eyepiece vignetting (light only).
        eyepieceRadius?.let { er ->
            val rr = er * min(width, height) / 2.0
            for (y in 0 until height) for (x in 0 until width) {
                val d = hypot(x - cx, y - cy)
                val f = when {
                    d < rr - 6 -> 1.0 - 0.25 * (d / rr) * (d / rr)
                    d < rr + 6 -> (1.0 - 0.25) * (rr + 6 - d) / 12.0
                    else -> 0.0
                }
                img[y * width + x] *= f
            }
        }
        // Dark current and hot pixels: sensor properties, fixed in sensor coordinates,
        // present also outside the eyepiece circle.
        for (i in img.indices) img[i] += darkCurrent * shot.thermal * t
        for ((hx, hy, rate) in hot) img[hy * width + hx] += rate * shot.thermal * t / 10.0
        // Cosmic rays.
        repeat(shot.cosmicRays) {
            val x = rnd.nextInt(width); val y = rnd.nextInt(height)
            img[y * width + x] += 8000.0
        }
        // Noise: Poisson (Gaussian approx.) + read noise, then ADU.
        val data = ShortArray(width * height)
        for (i in img.indices) {
            var e = img[i]
            if (!shot.noiseFree) {
                e += sqrt(max(0.0, e)) * gaussian(rnd)
                e += readNoise * gaussian(rnd)
            }
            val adu = (e / gain + blackLevel).roundToInt().coerceIn(0, whiteLevel)
            data[i] = adu.toShort()
        }
        val meta = FrameMeta(t, shot.iso, shot.timestampMs, shot.gyroRms)
        val bl = FloatArray(4) { blackLevel.toFloat() }
        return RawFrame(width, height, data, cfa, bl, whiteLevel.toFloat(), meta)
    }

    /** Noise-free reference of the sky (stars + extended + sky) in the sky frame, 1 s. */
    fun truth(fwhm: Double = 3.0, includeSky: Boolean = false): Image {
        val img = DoubleArray(width * height)
        if (includeSky) for (i in img.indices) img[i] = skyRate
        for (y in 0 until height) for (x in 0 until width) img[y * width + x] += extendedRate(x.toDouble(), y.toDouble())
        val shot = Shot(fwhm = fwhm)
        for (s in stars) addStar(img, s.x, s.y, s.rate, shot)
        return Image(width, height, FloatArray(img.size) { img[it].toFloat() })
    }

    private fun cloudFactor(x: Double, y: Double, shot: Shot): Double {
        // Smooth pseudo-random cloud field: low-frequency sines.
        val v = 0.5 + 0.25 * sin(x / 37.0 + shot.noiseSeed) + 0.25 * cos(y / 53.0 - shot.noiseSeed * 0.7)
        return if (v < shot.cloudCover) 0.03 else 1.0
    }

    /** Moffat (β = 3) star, optionally elongated, or a donut when defocused. */
    private fun addStar(img: DoubleArray, x: Double, y: Double, total: Double, shot: Shot) {
        val beta = 3.0
        val alpha = shot.fwhm / (2 * sqrt(2.0.pow(1 / beta) - 1))
        val elong = shot.elongation
        val ang = Math.toRadians(shot.elongationAngleDeg)
        val ca = cos(ang); val sa = sin(ang)
        val donut = shot.donutRadius
        val r = (if (donut > 0) donut + 3 * shot.fwhm else 5 * shot.fwhm * elong).toInt() + 2
        val x0 = max(0, (x - r).toInt()); val x1 = min(width - 1, (x + r).toInt())
        val y0 = max(0, (y - r).toInt()); val y1 = min(height - 1, (y + r).toInt())
        if (x0 > x1 || y0 > y1) return
        val vals = ArrayList<Triple<Int, Int, Double>>()
        var sum = 0.0
        for (yy in y0..y1) for (xx in x0..x1) {
            val dx = xx - x; val dy = yy - y
            val v = if (donut > 0) {
                val d = hypot(dx, dy)
                val inner = donut * 0.35
                if (d in inner..donut) 1.0 else exp(-((d - donut).coerceAtLeast(0.0) + (inner - d).coerceAtLeast(0.0)).pow(2) / 2.0)
            } else {
                val u = (dx * ca + dy * sa) / elong
                val w = -dx * sa + dy * ca
                (1 + (u * u + w * w) / (alpha * alpha)).pow(-beta)
            }
            vals.add(Triple(xx, yy, v)); sum += v
        }
        if (sum <= 0) return
        for ((xx, yy, v) in vals) img[yy * width + xx] += total * v / sum
    }

    /** Field rotation + drift for frame [n] of a session (alt-az, slow rotation about the centre). */
    fun sessionTransform(n: Int, degPerFrame: Double = 0.05, driftPxPerFrame: Double = 0.3): Similarity =
        Similarity.rotationAbout(n * degPerFrame, width / 2.0, height / 2.0, n * driftPxPerFrame, -n * driftPxPerFrame * 0.6)

    companion object {
        fun gaussian(r: Random): Double {
            val u1 = max(1e-12, r.nextDouble())
            val u2 = r.nextDouble()
            return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
        }
    }
}
