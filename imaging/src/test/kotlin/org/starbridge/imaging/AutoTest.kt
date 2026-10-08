package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Auto exposure, sensor measurements and focus must be right, not just plausible. */
class AutoTest {
    /** Point-source SNR per session for sub-exposure t (seconds), with readout overhead. */
    private fun sessionSnr(t: Double, sky: Double, rn: Double, signal: Double, overhead: Double, total: Double = 600.0): Double {
        val n = total / (t + overhead)
        return signal * t * sqrt(n) / sqrt(sky * t + rn * rn)
    }

    @Test
    fun autoExposureIsWithinTenPercentOfTheBruteForceOptimum() {
        data class Scenario(val name: String, val sky: Double, val rn: Double, val maxExp: Double, val rotation: Double, val radius: Double, val overhead: Double)
        val scenarios = listOf(
            Scenario("cielo oscuro", 2.0, 3.0, 30.0, 0.0, 800.0, 0.3),
            Scenario("ciudad", 120.0, 3.0, 30.0, 0.0, 800.0, 0.3),
            Scenario("luna", 400.0, 2.0, 30.0, 0.0, 800.0, 0.3),
            Scenario("objeto débil, sensor ruidoso", 5.0, 8.0, 30.0, 0.0, 800.0, 0.3),
            Scenario("cerca del cénit (rotación)", 5.0, 3.0, 30.0, 120.0, 900.0, 0.3),
            Scenario("cámara limitada a 1 s", 5.0, 3.0, 1.0, 0.0, 800.0, 0.05),
        )
        for (s in scenarios) {
            // "Measured" test frame: 2 s exposure.
            val t0 = 2.0
            val sigmaTotal = sqrt(s.sky * t0 + s.rn * s.rn)
            val advice = ExposureAdvisor.advise(ExposureInput(
                testExposureSec = t0, backgroundSigma = sigmaTotal, readNoise = s.rn,
                minExposureSec = 0.01, maxExposureSec = s.maxExp, rotationDegPerHour = s.rotation,
                maxRadiusPx = s.radius, overheadSec = s.overhead,
            ))
            // Brute force over the allowed exposures (rotation limit: 1 px at the edge).
            val rotLimit = if (s.rotation == 0.0) Double.MAX_VALUE else (1.0 / s.radius) / (s.rotation * Math.PI / 180 / 3600)
            val upper = minOf(s.maxExp, rotLimit)
            val grid = (1..3000).map { it * upper / 3000 }
            val best = grid.maxOf { sessionSnr(it, s.sky, s.rn, 1.0, s.overhead) }
            val got = sessionSnr(advice.exposureSec, s.sky, s.rn, 1.0, s.overhead)
            assertTrue(got >= 0.9 * best, "${s.name}: ${advice.explanation} gives ${got / best * 100}% of the optimum")
            assertTrue(advice.exposureSec <= upper * 1.0001, "${s.name}: exceeds the limit")
        }
    }

    @Test
    fun readNoiseIsMeasuredWithinTenPercent() {
        val sim = SkySimulator(width = 256, height = 256, seed = 2, readNoise = 4.5, hotPixels = 30, darkCurrent = 0.0)
        val a = RawConverter.convert(sim.render(SkySimulator.Shot(dark = true, exposureSec = 0.001, noiseSeed = 1)), Conversion()).frame.luminance
        val b = RawConverter.convert(sim.render(SkySimulator.Shot(dark = true, exposureSec = 0.001, noiseSeed = 2)), Conversion()).frame.luminance
        val rn = ExposureAdvisor.readNoise(a, b)
        assertTrue(abs(rn - 4.5) < 0.45, "read noise $rn vs 4.5")
    }

    @Test
    fun focusFitFindsTheBestPosition() {
        val rnd = Random(3)
        var ok = 0
        val runs = 200
        val step = 0.01
        repeat(runs) {
            val p0 = 0.1 + rnd.nextDouble() * 0.2
            val positions = (0..30).map { it * step }
            val hfrs = positions.map { p -> sqrt(1.6 * 1.6 + 400.0 * (p - p0) * (p - p0)) * (1 + 0.03 * SkySimulator.gaussian(rnd)) }
            val best = assertNotNull(Focus.bestPosition(positions, hfrs))
            if (abs(best - p0) <= step) ok++
        }
        assertTrue(ok >= 0.95 * runs, "focus within one step in $ok/$runs runs")
    }

    @Test
    fun halfFluxRadiusGrowsWithDefocusIncludingDonuts() {
        val sim = SkySimulator(width = 256, height = 256, seed = 5, hotPixels = 0)
        val values = listOf(0.0, 3.0, 5.0, 8.0).map { donut ->
            val f = RawConverter.convert(sim.render(SkySimulator.Shot(donutRadius = donut, noiseSeed = 9)), Conversion()).frame.luminance
            val det = StarDetector().detect(f, null, null, fwhmGuess = 3.0 + donut)
            Focus.measure(listOf(det.all.filter { it.snr > 10 })).hfr
        }
        for (i in 1 until values.size) assertTrue(values[i] > values[i - 1], "HFR must grow with defocus: $values")
    }

    @Test
    fun fieldRotationFormula() {
        // Due south at 45° altitude from latitude 40°: 15.041·cos40·cos180/cos45 ≈ −16.3 °/h.
        val r = fieldRotationDegPerHour(40.0, 180.0, 45.0)
        assertTrue(abs(r + 16.29) < 0.05, "rotation $r")
        // Due east: cos(90°) = 0 → no field rotation.
        assertTrue(abs(fieldRotationDegPerHour(40.0, 90.0, 30.0)) < 1e-9)
    }
}
