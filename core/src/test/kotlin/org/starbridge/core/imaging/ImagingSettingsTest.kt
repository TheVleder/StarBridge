package org.starbridge.core.imaging

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.starbridge.imaging.DenoiseLevel
import org.starbridge.imaging.QualityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImagingSettingsTest {
    private fun cam(w: Int, h: Int, mono: Boolean) = CameraInfo(
        "x", "x", "back", raw = true, manual = true, mono = mono, width = w, height = h,
        minExposureSec = 0.001, maxExposureSec = 30.0, minIso = 50, maxIso = 6400,
    )

    @Test
    fun autoBinningKeepsThePhoneOutOfMemoryTrouble() {
        // Huawei P20 Pro class sensors.
        val mono20 = cam(5120, 3840, mono = true)
        val bayer40 = cam(7296, 5472, mono = false)
        val b = ImagingSession.autoBinning(mono20)
        assertTrue((5120 / b).toLong() * (3840 / b) <= ImagingSession.MAX_WORKING_PIXELS, "mono bin $b")
        // 512 MB heap → 40 % for the engine: a colour stack must stay inside it.
        val budget = (512L shl 20) * 4 / 10
        val bc = ImagingSession.autoBinning(bayer40, color = true, memoryBytes = budget)
        val px = (7296 / 2 / bc).toLong() * (5472 / 2 / bc)
        assertTrue(px * ImagingSession.bytesPerPixel(true) <= budget, "colour bin $bc uses ${px * 170 shr 20} MB")
        // A forced 1×1 is raised when it cannot fit (10 MP colour would need ~1.7 GB).
        assertTrue(ImagingSession.autoBinning(bayer40, true, budget, maxPixels = Long.MAX_VALUE) >= 2)
        // A small sensor is never binned.
        assertEquals(1, ImagingSession.autoBinning(cam(1600, 1200, mono = false), true, budget))
    }

    @Test
    fun settingsAreValidatedAndMergedPartially() {
        val s = ImagingSettings().merge(buildJsonObject { put("exposureAuto", false); put("exposureSec", 12.5); put("quality", "normal"); put("denoise", "high") })
        assertFalse(s.exposureAuto)
        assertEquals(12.5, s.exposureSec)
        assertEquals(QualityMode.NORMAL, s.quality)
        assertEquals(DenoiseLevel.HIGH, s.denoise)
        assertEquals(ImagingSettings().iso, s.iso) // untouched
        assertFailsWith<IllegalArgumentException> { s.merge(buildJsonObject { put("exposureSec", -1.0) }) }
        assertFailsWith<IllegalArgumentException> { s.merge(buildJsonObject { put("binning", 9) }) }
        assertFailsWith<IllegalStateException> { s.merge(buildJsonObject { put("quality", "best") }) }
        // Out-of-range look values are clamped, not rejected.
        assertEquals(2.0, s.merge(buildJsonObject { put("contrast", 9.0) }).contrast)
        // Round trip through the stored JSON.
        assertEquals(s, ImagingSettings.parse(s.toJson().toString()))
        assertEquals(ImagingSettings(), ImagingSettings.parse("not json"))
        // What needs a new stack and what only a re-render.
        assertTrue(s.copy(binning = 2).needsNewStack(s))
        assertFalse(s.copy(contrast = 1.5).needsNewStack(s))
        assertTrue(s.copy(contrast = 1.5).renderChanged(s))
    }
}
