package org.starbridge.imaging

/** Color filter array layout of a sensor (top-left 2×2 block), or monochrome. */
enum class Cfa(val code: String) {
    RGGB("RGGB"), GRBG("GRBG"), GBRG("GBRG"), BGGR("BGGR"), MONO("MONO");

    /** Channel (0 = R, 1 = G, 2 = B) of the pixel at (x, y). */
    fun channelAt(x: Int, y: Int): Int {
        val c = code[(y and 1) * 2 + (x and 1)]
        return when (c) { 'R' -> 0; 'G' -> 1; else -> 2 }
    }
}

/**
 * A raw sensor frame as delivered by the camera: 16-bit values, black level not yet
 * subtracted. Mono sensors use [Cfa.MONO].
 */
class RawFrame(
    val width: Int,
    val height: Int,
    /** Unsigned 16-bit samples stored in a ShortArray (use `and 0xFFFF`). */
    val data: ShortArray,
    val cfa: Cfa,
    /** Black level per CFA position (2×2, row-major) in ADU. */
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    val meta: FrameMeta,
) {
    init {
        require(data.size == width * height)
        require(blackLevel.size == 4)
    }
}

/** Everything known about a frame apart from its pixels. */
data class FrameMeta(
    val exposureSec: Double,
    val iso: Int,
    val timestampMs: Long,
    /** RMS angular rate of the phone during the exposure (deg/s), null if unknown. */
    val gyroRmsDegPerSec: Double? = null,
    /** True if the mount reported movement (GoTo/slew) during the exposure. */
    val mountMoving: Boolean = false,
    val cameraId: String = "",
)

/** How a raw frame becomes a working frame. */
data class Conversion(
    /** Software binning factor applied after the CFA superpixel (1, 2 or 3). */
    val binning: Int = 1,
    /** Keep color (3 channels) for CFA sensors; mono sensors are always 1 channel. */
    val color: Boolean = false,
)

/** Working frame plus the mask of saturated pixels (true = usable). */
class WorkingFrame(val frame: Frame, val unsaturated: Mask, val meta: FrameMeta, val saturationLevel: Float)

object RawConverter {
    /**
     * Black level subtraction, saturation mask, CFA superpixel (each 2×2 block → 1 pixel,
     * no interpolation) and software binning (sum of b×b blocks).
     */
    fun convert(raw: RawFrame, conv: Conversion): WorkingFrame {
        val superFactor = if (raw.cfa == Cfa.MONO) 1 else 2
        val b = conv.binning.coerceIn(1, 4)
        val step = superFactor * b
        val w = raw.width / step
        val h = raw.height / step
        val colorOut = conv.color && raw.cfa != Cfa.MONO
        val nCh = if (colorOut) 3 else 1
        val out = List(nCh) { Image(w, h) }
        val mask = Mask.all(w, h)
        val satRaw = raw.whiteLevel * 0.95f
        val counts = IntArray(3)
        val sums = FloatArray(3)
        for (oy in 0 until h) for (ox in 0 until w) {
            sums.fill(0f); counts.fill(0)
            var saturated = false
            for (dy in 0 until step) for (dx in 0 until step) {
                val x = ox * step + dx
                val y = oy * step + dy
                val v = (raw.data[y * raw.width + x].toInt() and 0xFFFF).toFloat()
                if (v >= satRaw) saturated = true
                val pos = (y and 1) * 2 + (x and 1)
                val ch = if (raw.cfa == Cfa.MONO) 0 else raw.cfa.channelAt(x, y)
                sums[ch] += v - raw.blackLevel[pos]
                counts[ch]++
            }
            val i = oy * w + ox
            if (raw.cfa == Cfa.MONO) {
                out[0].data[i] = sums[0]
            } else if (colorOut) {
                // Sum per channel, rescaled to the same number of samples as R/B (green has 2×).
                for (c in 0 until 3) out[c].data[i] = if (counts[c] > 0) sums[c] / counts[c] * (step * step / 4f) else 0f
            } else {
                out[0].data[i] = sums[0] + sums[1] + sums[2]
            }
            if (saturated) mask.data[i] = 0
        }
        val samplesPerPixel = if (raw.cfa == Cfa.MONO) step * step else if (colorOut) step * step / 4 else step * step
        val satLevel = (raw.whiteLevel - raw.blackLevel.average().toFloat()) * 0.95f * samplesPerPixel
        return WorkingFrame(Frame(out), mask, raw.meta, satLevel)
    }
}
