package org.starbridge.core.pointing

import org.starbridge.core.astro.Astro
import org.starbridge.core.mount.AltAz
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Maps raw mount encoder angles (zero = wherever the scope was at power-on) to the real
 * apparent sky direction, and back.
 *
 *   a = azSign·A + azOffset,  e = E + altOffset          (encoder → corrected mount angles)
 *   v_sky = Rx(tiltEast) · Ry(tiltNorth) · dir(a, e)     (azimuth axis not vertical)
 *
 * dir(a, e) = (cos e · sin a, cos e · cos a, sin e) in (East, North, Up); azimuth from North
 * towards East.
 */
data class PointingParams(
    val azOffset: Double = 0.0,
    val altOffset: Double = 0.0,
    /** Tilt of the azimuth axis around the East axis (degrees). */
    val tiltEast: Double = 0.0,
    /** Tilt of the azimuth axis around the North axis (degrees). */
    val tiltNorth: Double = 0.0,
    /** +1 if encoder azimuth grows North→East like the sky, −1 if reversed. */
    val azSign: Int = 1,
) {
    fun mountToSky(mount: AltAz): AltAz {
        val a = azSign * mount.azDeg + azOffset
        val e = mount.altDeg + altOffset
        val v = rotate(direction(a, e), tiltEast, tiltNorth, inverse = false)
        return toAltAz(v)
    }

    fun skyToMount(sky: AltAz): AltAz {
        val v = rotate(direction(sky.azDeg, sky.altDeg), tiltEast, tiltNorth, inverse = true)
        val m = toAltAz(v)
        val az = Astro.norm360((m.azDeg - azOffset) * azSign)
        return AltAz(az, m.altDeg - altOffset)
    }

    companion object {
        private const val DEG = Math.PI / 180.0

        fun direction(azDeg: Double, altDeg: Double): DoubleArray {
            val a = azDeg * DEG
            val e = altDeg * DEG
            return doubleArrayOf(cos(e) * sin(a), cos(e) * cos(a), sin(e))
        }

        fun toAltAz(v: DoubleArray): AltAz {
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val alt = asin((v[2] / n).coerceIn(-1.0, 1.0)) / DEG
            val az = Astro.norm360(atan2(v[0], v[1]) / DEG)
            return AltAz(az, alt)
        }

        /** Rotation about East (x) then North (y); inverse applies the transpose. */
        private fun rotate(v: DoubleArray, tiltEastDeg: Double, tiltNorthDeg: Double, inverse: Boolean): DoubleArray {
            val tx = tiltEastDeg * DEG
            val ty = tiltNorthDeg * DEG
            fun rx(p: DoubleArray, t: Double) = doubleArrayOf(
                p[0], cos(t) * p[1] - sin(t) * p[2], sin(t) * p[1] + cos(t) * p[2],
            )
            fun ry(p: DoubleArray, t: Double) = doubleArrayOf(
                cos(t) * p[0] + sin(t) * p[2], p[1], -sin(t) * p[0] + cos(t) * p[2],
            )
            return if (!inverse) rx(ry(v, ty), tx) else ry(rx(v, -tx), -ty)
        }

        /** Great-circle separation in degrees. */
        fun separation(a: AltAz, b: AltAz): Double {
            val va = direction(a.azDeg, a.altDeg)
            val vb = direction(b.azDeg, b.altDeg)
            // atan2(|a x b|, a . b): accurate for tiny and large angles alike.
            val cx = va[1] * vb[2] - va[2] * vb[1]
            val cy = va[2] * vb[0] - va[0] * vb[2]
            val cz = va[0] * vb[1] - va[1] * vb[0]
            val dot = va[0] * vb[0] + va[1] * vb[1] + va[2] * vb[2]
            return atan2(sqrt(cx * cx + cy * cy + cz * cz), dot) / DEG
        }

        /** Signed shortest difference a − b in degrees (−180..180]. */
        fun angleDiff(a: Double, b: Double): Double {
            val d = Astro.norm360(a - b)
            return if (d > 180) d - 360 else d
        }

        internal fun abs180(x: Double) = abs(angleDiff(x, 0.0))
    }
}
