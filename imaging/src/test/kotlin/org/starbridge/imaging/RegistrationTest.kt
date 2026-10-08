package org.starbridge.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegistrationTest {
    private val sim = SkySimulator(width = 480, height = 480, seed = 3)
    private val detector = StarDetector()

    private fun detect(shot: SkySimulator.Shot): Detection {
        val wf = RawConverter.convert(sim.render(shot), Conversion())
        return detector.detect(wf.frame.luminance, null, wf.unsaturated)
    }

    @Test
    fun centroidsAreSubPixel() {
        val det = detect(SkySimulator.Shot(exposureSec = 10.0, noiseSeed = 11))
        val errors = ArrayList<Double>()
        for (s in det.stars.filter { it.snr > 20 }) {
            val truth = sim.stars.minBy { hypot(it.x - s.x, it.y - s.y) }
            val d = hypot(truth.x - s.x, truth.y - s.y)
            if (d < 2) errors.add(d) // nearest true star (ignores blends)
        }
        assertTrue(errors.size > 30, "matched ${errors.size}")
        val med = Stats.median(errors)
        assertTrue(med < 0.1, "median centroid error $med px")
    }

    /** Max position error of [t] vs [truth] over the field. */
    private fun fieldError(t: Similarity, truth: Similarity): Double {
        var worst = 0.0
        for (y in listOf(20.0, 240.0, 460.0)) for (x in listOf(20.0, 240.0, 460.0)) {
            worst = maxOf(worst, hypot(t.applyX(x, y) - truth.applyX(x, y), t.applyY(x, y) - truth.applyY(x, y)))
        }
        return worst
    }

    @Test
    fun registersFieldRotationAndDrift() {
        val ref = detect(SkySimulator.Shot(noiseSeed = 1))
        val reg = Registrar(ref.stars)
        var prev = Similarity.IDENTITY
        for (n in listOf(5, 10, 20, 40)) {
            val skyT = sim.sessionTransform(n, degPerFrame = 0.08, driftPxPerFrame = 0.5)
            val det = detect(SkySimulator.Shot(transform = skyT, noiseSeed = 100 + n))
            val r = assertNotNull(reg.register(det.stars, prev), "frame $n")
            // Frame → reference is exactly the frame → sky transform (reference = sky at n = 0).
            val err = fieldError(r.transform, skyT)
            assertTrue(err < 0.1, "frame $n: field error $err px (${r.method}, ${r.inliers} inliers)")
            assertTrue(abs(r.transform.angleDeg - skyT.angleDeg) < 0.005, "angle error at $n")
            prev = r.transform
        }
    }

    @Test
    fun blindRecoveryAfterABigJump() {
        val ref = detect(SkySimulator.Shot(noiseSeed = 1))
        val reg = Registrar(ref.stars)
        val jump = Similarity.rotationAbout(10.0, 240.0, 240.0, 60.0, -45.0)
        val det = detect(SkySimulator.Shot(transform = jump, noiseSeed = 5))
        // Wrong prediction on purpose (identity): must fall back to triangles.
        val r = assertNotNull(reg.register(det.stars, Similarity.IDENTITY))
        assertTrue(fieldError(r.transform, jump) < 0.15, "error ${fieldError(r.transform, jump)} method=${r.method} inliers=${r.inliers} rms=${r.rmsPx} t=${r.transform} truth=$jump stars=${det.stars.size} ref=${ref.stars.size}")
    }

    @Test
    fun noFalseMatchOnAnotherField() {
        val ref = detect(SkySimulator.Shot(noiseSeed = 1))
        val other = SkySimulator(width = 480, height = 480, seed = 99)
        val wf = RawConverter.convert(other.render(SkySimulator.Shot(noiseSeed = 2)), Conversion())
        val det = detector.detect(wf.frame.luminance, null, wf.unsaturated)
        val r = Registrar(ref.stars).register(det.stars, null)
        assertTrue(r == null, "must not register a different star field: $r")
    }
}
