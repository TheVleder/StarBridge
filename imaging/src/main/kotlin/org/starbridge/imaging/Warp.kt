package org.starbridge.imaging

/**
 * Resamples a frame into the reference (stack) geometry with inverse mapping: for every
 * output pixel, the source position is T⁻¹(p). Bilinear interpolation. Pixels whose source
 * falls outside the frame or outside [validMask] get coverage 0.
 */
object Warper {
    class Warped(val frame: Frame, val coverage: FloatArray)

    fun warp(src: Frame, frameToRef: Similarity, outW: Int, outH: Int, validMask: Mask?): Warped {
        val inv = frameToRef.inverse()
        val out = src.channels.map { Image(outW, outH) }
        val cov = FloatArray(outW * outH)
        val sw = src.width
        val sh = src.height
        for (y in 0 until outH) {
            // Incremental evaluation of the affine map along the row.
            var sx = inv.applyX(0.0, y.toDouble())
            var sy = inv.applyY(0.0, y.toDouble())
            val dxdx = inv.a
            val dydx = inv.b
            for (x in 0 until outW) {
                val o = y * outW + x
                if (sx >= 0 && sy >= 0 && sx <= sw - 1 && sy <= sh - 1) {
                    val x0 = sx.toInt().coerceAtMost(sw - 2)
                    val y0 = sy.toInt().coerceAtMost(sh - 2)
                    val fx = (sx - x0).toFloat()
                    val fy = (sy - y0).toFloat()
                    val i = y0 * sw + x0
                    var ok = true
                    if (validMask != null) {
                        ok = validMask[i] && validMask[i + 1] && validMask[i + sw] && validMask[i + sw + 1]
                    }
                    if (ok) {
                        for (c in out.indices) {
                            val d = src.channels[c].data
                            out[c].data[o] = (d[i] * (1 - fx) + d[i + 1] * fx) * (1 - fy) + (d[i + sw] * (1 - fx) + d[i + sw + 1] * fx) * fy
                        }
                        cov[o] = 1f
                    }
                }
                sx += dxdx
                sy += dydx
            }
        }
        return Warped(Frame(out), cov)
    }
}
