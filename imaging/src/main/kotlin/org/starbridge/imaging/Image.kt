package org.starbridge.imaging

/** Single-channel float image, row-major. Values are linear (ADU after black level). */
class Image(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(width > 0 && height > 0 && data.size == width * height) { "Bad image size" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]
    operator fun set(x: Int, y: Int, v: Float) {
        data[y * width + x] = v
    }

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun copy() = Image(width, height, data.copyOf())

    /** Bilinear sample; NaN outside the image. */
    fun bilinear(x: Double, y: Double): Float {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return Float.NaN
        val x0 = x.toInt().coerceAtMost(width - 2).coerceAtLeast(0)
        val y0 = y.toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val i = y0 * width + x0
        val a = data[i]
        val b = data[i + 1]
        val c = data[i + width]
        val d = data[i + width + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    companion object {
        fun filled(width: Int, height: Int, value: Float) = Image(width, height, FloatArray(width * height) { value })
    }
}

/**
 * A working frame: 1 channel (mono / luminance) or 3 channels (RGB). Detection and
 * registration always use [luminance].
 */
class Frame(val channels: List<Image>) {
    init {
        require(channels.size == 1 || channels.size == 3) { "1 or 3 channels" }
        require(channels.all { it.width == channels[0].width && it.height == channels[0].height })
    }

    val width get() = channels[0].width
    val height get() = channels[0].height
    val isColor get() = channels.size == 3

    val luminance: Image by lazy {
        if (!isColor) channels[0] else {
            val r = channels[0].data
            val g = channels[1].data
            val b = channels[2].data
            Image(width, height, FloatArray(r.size) { (r[it] + g[it] + b[it]) / 3f })
        }
    }

    fun copy() = Frame(channels.map { it.copy() })
}

/** Per-pixel boolean mask stored as bytes (1 = valid / set). */
class Mask(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height) { 1 }) {
    operator fun get(x: Int, y: Int) = data[y * width + x].toInt() != 0
    operator fun get(i: Int) = data[i].toInt() != 0
    operator fun set(x: Int, y: Int, v: Boolean) {
        data[y * width + x] = if (v) 1 else 0
    }

    fun count() = data.count { it.toInt() != 0 }

    companion object {
        fun all(width: Int, height: Int) = Mask(width, height)
        fun none(width: Int, height: Int) = Mask(width, height, ByteArray(width * height))
    }
}
