package org.starbridge.imaging

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A phone at the eyepiece often sees just one or two stars, the bright one saturated. */
class SparseFieldTest {
    private fun star(x: Double, y: Double, flux: Double, saturated: Boolean = false) =
        Star(x, y, flux, flux / 10, 50.0, 3.0, 1.5, 1.1, saturated)

    private fun moved(s: Star, t: Similarity) = s.copy(x = t.applyX(s.x, s.y), y = t.applyY(s.x, s.y))

    @Test
    fun twoStarsGiveShiftAndRotation() {
        val ref = listOf(star(100.0, 120.0, 9000.0), star(260.0, 300.0, 4000.0))
        // the frame is the reference seen through a small rotation + drift (frame → reference = truth)
        val truth = Similarity.rotationAbout(0.4, 200.0, 200.0, 3.0, -2.0)
        val frame = ref.map { moved(it, truth.inverse()) }
        val r = assertNotNull(Registrar(ref).register(frame, Similarity.IDENTITY))
        assertEquals("sparse", r.method)
        assertEquals(2, r.inliers)
        for (p in listOf(0.0 to 0.0, 400.0 to 400.0)) {
            val e = hypot(r.transform.applyX(p.first, p.second) - truth.applyX(p.first, p.second),
                r.transform.applyY(p.first, p.second) - truth.applyY(p.first, p.second))
            assertTrue(e < 0.05, "error $e px")
        }
    }

    @Test
    fun oneSaturatedStarGivesTheShift() {
        val ref = listOf(star(150.0, 150.0, 90000.0, saturated = true))
        val frame = listOf(star(155.5, 147.0, 88000.0, saturated = true))
        val r = assertNotNull(Registrar(ref).register(frame, null))
        assertEquals(150.0, r.transform.applyX(155.5, 147.0), 1e-9)
        assertEquals(150.0, r.transform.applyY(155.5, 147.0), 1e-9)
    }

    @Test
    fun aWildJumpIsRefused() {
        val ref = listOf(star(100.0, 100.0, 9000.0))
        val frame = listOf(star(300.0, 260.0, 9000.0))
        assertNull(Registrar(ref).register(frame, Similarity.IDENTITY))
    }

    @Test
    fun twoStarFieldStacksEndToEnd() {
        // a seed whose two stars are both bright and well inside the frame
        val sim = (1..400).asSequence()
            .map { SkySimulator(width = 256, height = 256, seed = it, starCount = 2, hotPixels = 0) }
            .first { s -> s.stars.all { it.rate > 500 && it.x in 50.0..206.0 && it.y in 50.0..206.0 } }
        val ls = LiveStacker(StackConfig(eyepieceMask = false))
        for (k in 0 until 12) ls.process(sim.render(SkySimulator.Shot(transform = sim.sessionTransform(k, 0.05, 0.4), noiseSeed = 400 + k)))
        ls.flush()
        val st = ls.status()
        assertTrue(st.accepted + st.downweighted >= 11, "used ${st.accepted + st.downweighted} of 12")
    }
}
