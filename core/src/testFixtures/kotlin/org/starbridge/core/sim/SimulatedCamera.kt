package org.starbridge.core.sim

import kotlinx.coroutines.delay
import org.starbridge.core.imaging.CameraInfo
import org.starbridge.core.imaging.CameraSource
import org.starbridge.core.imaging.CaptureSpec
import org.starbridge.imaging.Cfa
import org.starbridge.imaging.RawFrame
import org.starbridge.imaging.SkySimulator
import kotlin.math.abs

/**
 * A fake phone camera looking at a synthetic sky through an eyepiece (dev server and tests):
 * a colour Bayer camera, a monochrome one and a front camera without manual control.
 * Exposures take [timeScale] × their real duration.
 */
class SimulatedCamera(
    private val timeScale: Double = 0.15,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Best lens focus (diopters) of the simulated phone through the eyepiece. */
    private val bestFocus: Double = 0.35,
) : CameraSource {
    private val infos = listOf(
        CameraInfo(
            "0", "Trasera · color 1,9 MP (simulada)", "back", raw = true, manual = true, mono = false,
            width = 1600, height = 1200, minExposureSec = 0.0001, maxExposureSec = 30.0, minIso = 50, maxIso = 6400,
            maxAnalogIso = 800, focusable = true, minFocusDiopters = 10.0, focalLengthMm = 5.6,
        ),
        CameraInfo(
            "2", "Trasera · monocromo 1,1 MP (simulada)", "back", raw = true, manual = true, mono = true,
            width = 1200, height = 900, minExposureSec = 0.0001, maxExposureSec = 30.0, minIso = 50, maxIso = 6400,
            maxAnalogIso = 800, focusable = true, minFocusDiopters = 10.0, focalLengthMm = 5.6,
        ),
        CameraInfo(
            "1", "Delantera (simulada)", "front", raw = false, manual = false, mono = false,
            width = 640, height = 480, minExposureSec = 0.001, maxExposureSec = 0.5, minIso = 100, maxIso = 1600,
            notes = listOf("sin RAW", "sin exposición manual"),
        ),
    )
    private var open: CameraInfo? = null
    private var frame = 0
    private val sims = HashMap<String, SkySimulator>()

    /** Mount nudges (dev server): shift of the field in pixels added to the session drift. */
    @Volatile var offsetX = 0.0
    @Volatile var offsetY = 0.0

    override fun cameras() = infos

    override suspend fun open(id: String): CameraInfo {
        val info = infos.first { it.id == id }
        delay(200)
        open = info
        return info
    }

    override suspend fun capture(spec: CaptureSpec): RawFrame {
        val info = open ?: throw IllegalStateException("Cámara cerrada")
        val exp = if (info.manual) spec.exposureSec.coerceIn(info.minExposureSec, info.maxExposureSec) else info.maxExposureSec
        val iso = if (info.manual) spec.iso.coerceIn(info.minIso, info.maxIso) else 800
        delay(((exp * timeScale) * 1000).toLong().coerceAtLeast(150))
        val key = "${info.id}/$iso"
        val sim = synchronized(sims) {
            sims.getOrPut(key) {
                SkySimulator(
                    width = info.width, height = info.height, seed = 11, starCount = (info.width * info.height / 3500),
                    gain = 1.0 * 800 / iso, cfa = if (info.mono || !info.raw) Cfa.MONO else Cfa.RGGB,
                    eyepieceRadius = 0.97, galaxyRate = 6.0, nebulaRate = 4.0, hotPixels = 80,
                    whiteLevel = if (info.raw) 16383 else 255, blackLevel = if (info.raw) 64 else 0,
                    skyRate = if (info.raw) 40.0 else 4.0,
                )
            }
        }
        val k = frame++
        val t = sim.sessionTransform(k, 0.03, 0.25).let { it.copy(tx = it.tx + offsetX, ty = it.ty + offsetY) }
        val defocus = abs(spec.focusDiopters - bestFocus) * 14
        return sim.render(
            SkySimulator.Shot(
                exposureSec = exp, transform = t, noiseSeed = 1000 + k, timestampMs = clock(),
                donutRadius = if (defocus > 2.5) defocus else 0.0, fwhm = 3.0 + defocus.coerceAtMost(2.5) * 0.6,
                gyroRms = 0.02, iso = iso,
            ),
        )
    }

    override suspend fun close() {
        open = null
    }
}
