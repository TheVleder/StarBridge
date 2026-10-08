package org.starbridge.imaging

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Similarity transform (rotation, uniform scale, translation):
 *   x' = a·x − b·y + tx
 *   y' = b·x + a·y + ty
 * with a = s·cos θ, b = s·sin θ.
 */
data class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double) {
    val scale: Double get() = hypot(a, b)
    val angleRad: Double get() = atan2(b, a)
    val angleDeg: Double get() = Math.toDegrees(angleRad)

    fun applyX(x: Double, y: Double) = a * x - b * y + tx
    fun applyY(x: Double, y: Double) = b * x + a * y + ty

    fun inverse(): Similarity {
        val d = a * a + b * b
        val ia = a / d
        val ib = -b / d
        return Similarity(ia, ib, -(ia * tx - ib * ty), -(ib * tx + ia * ty))
    }

    /** this ∘ other: first [other], then this. */
    fun compose(other: Similarity) = Similarity(
        a * other.a - b * other.b,
        b * other.a + a * other.b,
        a * other.tx - b * other.ty + tx,
        b * other.tx + a * other.ty + ty,
    )

    /** Same transform with the scale forced into [lo, hi] (keeps angle and the centroid mapping roughly). */
    fun withScaleClamped(lo: Double, hi: Double): Similarity {
        val s = scale
        if (s in lo..hi) return this
        val k = s.coerceIn(lo, hi) / s
        return copy(a = a * k, b = b * k)
    }

    companion object {
        val IDENTITY = Similarity(1.0, 0.0, 0.0, 0.0)

        fun of(angleDeg: Double, scale: Double, tx: Double, ty: Double): Similarity {
            val t = Math.toRadians(angleDeg)
            return Similarity(scale * cos(t), scale * sin(t), tx, ty)
        }

        /** Rotation by [angleDeg] about (cx, cy) followed by a shift (dx, dy). */
        fun rotationAbout(angleDeg: Double, cx: Double, cy: Double, dx: Double = 0.0, dy: Double = 0.0): Similarity {
            val r = of(angleDeg, 1.0, 0.0, 0.0)
            return Similarity(r.a, r.b, cx - (r.a * cx - r.b * cy) + dx, cy - (r.b * cx + r.a * cy) + dy)
        }

        /**
         * Weighted least-squares similarity mapping p[i] → q[i] (Umeyama, closed form).
         * Returns null when degenerate.
         */
        fun fit(px: DoubleArray, py: DoubleArray, qx: DoubleArray, qy: DoubleArray, w: DoubleArray? = null): Similarity? {
            val n = px.size
            if (n < 2) return null
            var sw = 0.0; var mpx = 0.0; var mpy = 0.0; var mqx = 0.0; var mqy = 0.0
            for (i in 0 until n) {
                val wi = w?.get(i) ?: 1.0
                sw += wi; mpx += wi * px[i]; mpy += wi * py[i]; mqx += wi * qx[i]; mqy += wi * qy[i]
            }
            if (sw <= 0) return null
            mpx /= sw; mpy /= sw; mqx /= sw; mqy /= sw
            var sxx = 0.0; var sxy = 0.0; var spp = 0.0
            for (i in 0 until n) {
                val wi = w?.get(i) ?: 1.0
                val ax = px[i] - mpx; val ay = py[i] - mpy
                val bx = qx[i] - mqx; val by = qy[i] - mqy
                sxx += wi * (ax * bx + ay * by)
                sxy += wi * (ax * by - ay * bx)
                spp += wi * (ax * ax + ay * ay)
            }
            if (spp < 1e-9) return null
            val a = sxx / spp
            val b = sxy / spp
            return Similarity(a, b, mqx - (a * mpx - b * mpy), mqy - (b * mpx + a * mpy))
        }

        fun distance(t: Similarity, x: Double, y: Double, qx: Double, qy: Double) =
            sqrt((t.applyX(x, y) - qx).let { it * it } + (t.applyY(x, y) - qy).let { it * it })
    }
}
