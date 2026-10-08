package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CalibrationTest {
    @Suppress("UNCHECKED_CAST")
    private fun hotPixels(sim: SkySimulator): List<Triple<Int, Int, Double>> =
        SkySimulator::class.java.getDeclaredField("hot").apply { isAccessible = true }.get(sim) as List<Triple<Int, Int, Double>>

    @Test
    fun learnsEveryHotPixelIncludingPairsBordersAndNearStars() {
        val sim = SkySimulator(width = 320, height = 320, seed = 13, hotPixels = 80)
        val dm = DefectMap(320, 320)
        repeat(3) { k ->
            val f = RawConverter.convert(sim.render(SkySimulator.Shot(transform = sim.sessionTransform(k, 0.25, 0.6), noiseSeed = 500 + k)), Conversion()).frame.luminance
            dm.learn(f, Stats.robustNoise(f, null).toFloat())
        }
        val missed = hotPixels(sim).filter { (x, y, _) -> !dm.isDefect(y * 320 + x) }
        assertTrue(missed.isEmpty(), "hot pixels not learnt: $missed")
    }

    @Test
    fun perfectlyTrackedStarsAreNeverTakenForDefects() {
        // Stars fixed on the sensor (perfect tracking), sharp and also undersampled.
        for (fwhm in listOf(1.6, 2.2, 3.0)) {
            val sim = SkySimulator(width = 256, height = 256, seed = 4, hotPixels = 0)
            val dm = DefectMap(256, 256)
            repeat(6) { k ->
                val f = RawConverter.convert(sim.render(SkySimulator.Shot(fwhm = fwhm, noiseSeed = 10 + k)), Conversion()).frame.luminance
                dm.learn(f, Stats.robustNoise(f, null).toFloat())
            }
            val onStars = sim.stars.count { s ->
                val x = s.x.toInt(); val y = s.y.toInt()
                (-1..1).any { dy -> (-1..1).any { dx -> x + dx in 0 until 256 && y + dy in 0 until 256 && dm.isDefect((y + dy) * 256 + x + dx) } }
            }
            assertEquals(0, onStars, "FWHM $fwhm: stars taken for defects")
        }
    }

    @Test
    fun darkScalingAndEyepieceMask() {
        val sim = SkySimulator(width = 256, height = 256, seed = 6, hotPixels = 50, eyepieceRadius = 0.8)
        val conv = Conversion()
        val builder = DarkBuilder(10.0, 800)
        repeat(8) { k -> builder.add(RawConverter.convert(sim.render(SkySimulator.Shot(dark = true, noiseSeed = 900 + k)), conv).frame) }
        val dark = assertNotNull(builder.build())
        assertTrue(dark.defects.size >= 45, "defects in dark: ${dark.defects.size}")
        // A warmer light frame: the optimal dark scale must follow the temperature.
        val light = RawConverter.convert(sim.render(SkySimulator.Shot(thermal = 1.3, noiseSeed = 5)), conv).frame
        val k = Calibration.subtractDark(light, dark)
        assertTrue(abs(k - 1.3) < 0.1, "dark scale $k")
        val mask = assertNotNull(Calibration.eyepieceMask(RawConverter.convert(sim.render(SkySimulator.Shot(noiseSeed = 6)), conv).frame.luminance))
        val r = 0.8 * 128
        // Inside the circle (minus margin) is valid, outside is not.
        assertTrue(mask[128, 128] && mask[(128 + r * 0.8).toInt(), 128])
        assertTrue(!mask[2, 2] && !mask[(128 + r * 1.1).toInt().coerceAtMost(255), 128])
        assertTrue(hypot(1.0, 1.0) > 0)
    }
}
