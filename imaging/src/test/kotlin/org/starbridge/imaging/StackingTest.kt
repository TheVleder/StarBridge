package org.starbridge.imaging

import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The stack must really get better, without blurring and without artifacts. */
class StackingTest {
    private fun run(sim: SkySimulator, n: Int, shot: (Int) -> SkySimulator.Shot, config: StackConfig = StackConfig(eyepieceMask = false)): LiveStacker {
        val ls = LiveStacker(config)
        for (k in 0 until n) ls.process(sim.render(shot(k)))
        return ls
    }

    /** Mask without the stars of the simulated field (radius 12 px) and without the border. */
    private fun backgroundMask(sim: SkySimulator): Mask {
        val m = Calibration.borderMask(sim.width, sim.height, 24)
        for (s in sim.stars) for (dy in -12..12) for (dx in -12..12) {
            val x = s.x.toInt() + dx; val y = s.y.toInt() + dy
            if (x in 0 until sim.width && y in 0 until sim.height && dx * dx + dy * dy <= 144) m[x, y] = false
        }
        return m
    }

    /** Noise σ of the current stack, measured on star-free background. */
    private fun stackSigma(ls: LiveStacker, sim: SkySimulator): Double =
        Stats.robustNoise(ls.stackFrame()!!.luminance, backgroundMask(sim))

    @Test
    fun noiseFallsAsOneOverSqrtN() {
        val sim = SkySimulator(width = 320, height = 320, seed = 21, hotPixels = 0)
        // Integer-pixel shifts: the resampling is then exact, so the pure √N law is visible.
        val shot = { k: Int -> SkySimulator.Shot(transform = Similarity(1.0, 0.0, (k % 3).toDouble(), (k % 2).toDouble()), noiseSeed = 1000 + k) }
        // Single-frame noise, measured the same way on one calibrated frame.
        val one = RawConverter.convert(sim.render(shot(0)), Conversion()).frame.luminance
        val s1 = Stats.robustNoise(one, backgroundMask(sim))
        for (n in listOf(4, 16, 64)) {
            val ls = run(sim, n, shot)
            val ratio = s1 / stackSigma(ls, sim)
            val expected = sqrt(n.toDouble())
            assertTrue(ratio in expected * 0.9..expected * 1.1, "N=$n: gain $ratio, expected $expected ±10 %")
            val reported = ls.status().snrGain
            assertTrue(reported in expected * 0.85..expected * 1.15, "N=$n: reported gain $reported")
        }
    }

    @Test
    fun invisibleGalaxyAppears() {
        // Per-frame SNR of a 3×3 box at the galaxy core ≈ 1.5: invisible in one frame.
        val sim = SkySimulator(width = 320, height = 320, seed = 5, hotPixels = 0, galaxyRate = 1.0, starCount = 150)
        val shot = { k: Int -> SkySimulator.Shot(transform = sim.sessionTransform(k, 0.03, 0.2), noiseSeed = 77 + k) }
        fun boxSnr(ls: LiveStacker): Double {
            val f = ls.stackFrame()!!.luminance
            val bg = Background.estimate(f, Calibration.borderMask(f.width, f.height, 24))
            var s = 0.0
            val gx = sim.galaxyX.toInt(); val gy = sim.galaxyY.toInt()
            for (dy in -1..1) for (dx in -1..1) s += f[gx + dx, gy + dy] - bg.at(gx + dx, gy + dy)
            return (s / 9) / (Stats.robustNoise(f, Calibration.borderMask(f.width, f.height, 24)) / 3.0)
        }
        val one = run {
            val f = RawConverter.convert(sim.render(shot(0)), Conversion()).frame.luminance
            val bg = Background.estimate(f, Calibration.borderMask(f.width, f.height, 24))
            var s = 0.0
            val gx = sim.galaxyX.toInt(); val gy = sim.galaxyY.toInt()
            for (dy in -1..1) for (dx in -1..1) s += f[gx + dx, gy + dy] - bg.at(gx + dx, gy + dy)
            (s / 9) / (Stats.robustNoise(f, Calibration.borderMask(f.width, f.height, 24)) / 3.0)
        }
        val many = boxSnr(run(sim, 64, shot))
        assertTrue(one < 2.5, "should be invisible in one frame: SNR $one")
        assertTrue(many >= 6.0, "should be clearly visible after 64 frames: SNR $many")
    }

    @Test
    fun stackIsNotBlurred() {
        val sim = SkySimulator(width = 384, height = 384, seed = 8, hotPixels = 0)
        val ls = run(sim, 20, { k -> SkySimulator.Shot(transform = sim.sessionTransform(k, 0.07, 0.37), noiseSeed = 300 + k) })
        val st = ls.status()
        assertTrue(st.accepted + st.downweighted >= 19, "frames used: ${st.accepted + st.downweighted}")
        assertTrue(st.stackFwhm <= st.singleFwhm * 1.1, "stack FWHM ${st.stackFwhm} vs single ${st.singleFwhm}")
    }

    @Test
    fun satellitesCosmicRaysAndHotPixelsDisappear() {
        val sim = SkySimulator(width = 320, height = 320, seed = 13, hotPixels = 80)
        val ls = run(sim, 24, { k ->
            SkySimulator.Shot(
                transform = sim.sessionTransform(k, 0.25, 0.6), noiseSeed = 500 + k,
                satellite = k == 2 || k == 9, cosmicRays = 20,
            )
        })
        val f = ls.stackFrame()!!.luminance
        // Measure only where every frame contributed (field rotation leaves the edges partial).
        val bg = Background.estimate(f, Calibration.borderMask(f.width, f.height, 40))
        // Count strong outliers far from any real star.
        var artifacts = 0
        for (y in 40 until f.height - 40) for (x in 40 until f.width - 40) {
            val v = f[x, y] - bg.at(x, y)
            if (v < 8 * bg.sigma) continue
            // Bright stars have wide Moffat wings: exclude a radius growing with brightness.
            val nearStar = sim.stars.any { hypot(it.x - x, it.y - y) < 6 + 3 * sqrt(it.rate / 1000) }
            if (!nearStar) artifacts++
        }
        assertTrue(artifacts <= 3, "artifacts left: $artifacts")
    }
}
