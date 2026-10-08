package org.starbridge.imaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end: colour sensors, afocal eyepiece, rendering, export, recovery, performance. */
class PipelineTest {
    @Test
    fun colourBayerSensorThroughTheEyepiece() {
        val sim = SkySimulator(width = 400, height = 400, seed = 17, cfa = Cfa.RGGB, eyepieceRadius = 0.9, hotPixels = 40)
        val ls = LiveStacker(StackConfig(conversion = Conversion(color = true)))
        for (k in 0 until 12) ls.process(sim.render(SkySimulator.Shot(transform = sim.sessionTransform(k, 0.05, 0.3), noiseSeed = 50 + k)))
        val st = ls.status()
        assertTrue(st.accepted + st.downweighted >= 11, "used ${st.accepted + st.downweighted} of 12")
        assertTrue(st.maskRadiusKnown)
        val stack = assertNotNull(ls.stackFrame())
        assertEquals(3, stack.channels.size)
        assertEquals(200, stack.width) // 2×2 superpixel
        val pic = assertNotNull(ls.renderStack(RenderSettings(maxSize = 160)))
        assertTrue(pic.width <= 160 && pic.height <= 160)
        // The corners (outside the eyepiece) are black after cropping/masking.
        assertTrue(st.snrGain > 2.8, "gain ${st.snrGain}") // √11 ≈ 3.3 expected
    }

    @Test
    fun renderingAndExport() {
        val sim = SkySimulator(width = 256, height = 256, seed = 3, galaxyRate = 3.0)
        val ls = LiveStacker(StackConfig(eyepieceMask = false))
        for (k in 0 until 6) ls.process(sim.render(SkySimulator.Shot(transform = sim.sessionTransform(k), noiseSeed = k)))
        val pic = assertNotNull(ls.renderStack(RenderSettings()))
        // Stretched: the background sits near the target, not black and not white.
        val lum = pic.argb.map { ((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF) }.sorted()
        val med = lum[lum.size / 2] / 3.0 / 255
        assertTrue(med in 0.12..0.4, "stretched background $med")
        val night = assertNotNull(ls.renderStack(RenderSettings(nightRed = true)))
        assertTrue(night.argb.all { (it and 0xFFFF) == 0 }, "night mode must be pure red")
        val single = assertNotNull(ls.renderReference(RenderSettings()))
        assertTrue(single.width > 0)
        val fits = Export.fits(assertNotNull(ls.stackFrame()), mapOf("EXPTIME" to "10.0", "OBJECT" to "M31"))
        assertEquals(0, fits.size % 2880)
        assertEquals("SIMPLE  =", String(fits, 0, 9, Charsets.US_ASCII))
        val (cropped, unit) = Export.linearUnit(assertNotNull(ls.stackFrame()), null, null)
        val tif = Export.tiff16(cropped.width, cropped.height, unit)
        assertEquals("II", String(tif, 0, 2, Charsets.US_ASCII))
        assertEquals(8 + 2 + 10 * 12 + 4 + 8 + cropped.width * cropped.height * 2, tif.size)
        assertTrue(unit[0].maxOrNull() == 1f && unit[0].count { it > 0f } > unit[0].size * 0.9, "linear export keeps the background")
        val png = Export.png16(4, 3, listOf(FloatArray(12) { it / 12f }))
        assertEquals(0x89.toByte(), png[0])
        assertEquals("PNG", String(png, 1, 3, Charsets.US_ASCII))
    }

    @Test
    fun rejectedFrameCanBeRecovered() {
        val sim = SkySimulator(width = 256, height = 256, seed = 12, hotPixels = 0)
        val ls = LiveStacker(StackConfig(eyepieceMask = false))
        for (k in 0 until 8) ls.process(sim.render(SkySimulator.Shot(transform = sim.sessionTransform(k), noiseSeed = 200 + k)))
        val recs = ls.process(sim.render(SkySimulator.Shot(transform = sim.sessionTransform(8), noiseSeed = 300, gyroRms = 3.0)))
        val bad = recs.single()
        assertEquals(Verdict.REJECT, bad.verdict)
        val rec = assertNotNull(ls.recover(bad.id))
        assertEquals(Verdict.DOWNWEIGHT, rec.verdict)
        assertTrue(ls.records.single { it.id == bad.id }.reasons.contains("Recuperada por el usuario"))
    }

    @Test
    fun performanceBudget() {
        // Reference timing on a PC for a 2 MP working frame (e.g. 20 MP mono binned 3×3 or a
        // 12 MP Bayer superpixel + 2×2 bin). The phone is ~5–10× slower: still far below the
        // 30 % of a 10–30 s exposure.
        val sim = SkySimulator(width = 1600, height = 1200, seed = 1, starCount = 900, hotPixels = 100)
        val ls = LiveStacker(StackConfig(eyepieceMask = false))
        val raws = (0 until 9).map { k -> sim.render(SkySimulator.Shot(transform = sim.sessionTransform(k), noiseSeed = k)) }
        raws.take(5).forEach { ls.process(it) } // warm-up (JIT)
        // Best of 4: other tests running in parallel / GC pauses must not make this flaky.
        val ms = raws.drop(5).minOf { raw ->
            val t0 = System.nanoTime()
            ls.process(raw)
            (System.nanoTime() - t0) / 1e6
        }
        println("2 MP frame processed in ${"%.0f".format(ms)} ms")
        assertTrue(ms < 600, "2 MP frame took $ms ms on the PC")
    }
}
