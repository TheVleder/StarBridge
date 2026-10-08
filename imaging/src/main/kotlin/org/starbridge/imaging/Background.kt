package org.starbridge.imaging

/**
 * Smooth sky background: σ-clipped median per tile, 3×3 median of the tile grid, bilinear
 * interpolation between tile centres. Plus the noise σ (MAD) of the background-subtracted image.
 */
class Background(
    val width: Int,
    val height: Int,
    private val tile: Int,
    private val gx: Int,
    private val gy: Int,
    private val grid: FloatArray,
    /** Noise σ of (image − background) in the masked area. */
    val sigma: Float,
    /** Median background level. */
    val level: Float,
) {
    /** Background value at pixel (x, y). */
    fun at(x: Int, y: Int): Float {
        val fx = ((x + 0.5f) / tile - 0.5f).coerceIn(0f, (gx - 1).toFloat())
        val fy = ((y + 0.5f) / tile - 0.5f).coerceIn(0f, (gy - 1).toFloat())
        val x0 = fx.toInt().coerceAtMost(maxOf(0, gx - 2))
        val y0 = fy.toInt().coerceAtMost(maxOf(0, gy - 2))
        val x1 = minOf(x0 + 1, gx - 1)
        val y1 = minOf(y0 + 1, gy - 1)
        val tx = fx - x0
        val ty = fy - y0
        val a = grid[y0 * gx + x0]
        val b = grid[y0 * gx + x1]
        val c = grid[y1 * gx + x0]
        val d = grid[y1 * gx + x1]
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty
    }

    /** Full-resolution background image. */
    fun toImage(): Image {
        val img = Image(width, height)
        for (y in 0 until height) for (x in 0 until width) img.data[y * width + x] = at(x, y)
        return img
    }

    companion object {
        fun estimate(img: Image, mask: Mask?, tileSize: Int = 64): Background {
            val tile = tileSize.coerceAtMost(minOf(img.width, img.height) / 2).coerceAtLeast(8)
            val gx = maxOf(1, img.width / tile)
            val gy = maxOf(1, img.height / tile)
            val raw = FloatArray(gx * gy) { Float.NaN }
            val buf = FloatArray(tile * tile * 2)
            for (ty in 0 until gy) for (tx in 0 until gx) {
                var n = 0
                val x0 = tx * img.width / gx
                val x1 = (tx + 1) * img.width / gx
                val y0 = ty * img.height / gy
                val y1 = (ty + 1) * img.height / gy
                // Every other pixel in each direction: plenty for a robust median, 4× faster.
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val i = y * img.width + x
                        val v = img.data[i]
                        if ((mask == null || mask[i]) && !v.isNaN() && n < buf.size) buf[n++] = v
                        x += 2
                    }
                    y += 2
                }
                if (n >= 16) raw[ty * gx + tx] = Stats.clipped(buf, n, 2.5f, 3).median
            }
            // Fill tiles without data (outside the eyepiece) with the global median of tiles.
            val valid = raw.filter { !it.isNaN() }.toFloatArray()
            val global = if (valid.isEmpty()) 0f else Stats.median(valid)
            for (i in raw.indices) if (raw[i].isNaN()) raw[i] = global
            // 3×3 median of the grid removes tiles dominated by a bright object.
            val grid = FloatArray(gx * gy)
            val nb = FloatArray(9)
            for (ty in 0 until gy) for (tx in 0 until gx) {
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = tx + dx
                    val yy = ty + dy
                    if (xx in 0 until gx && yy in 0 until gy) nb[n++] = raw[yy * gx + xx]
                }
                grid[ty * gx + tx] = Stats.medianInPlace(nb, n)
            }
            val bg = Background(img.width, img.height, img.width / gx, gx, gy, grid, 0f, global)
            // Noise of the residual (sampled).
            val step = maxOf(1, img.data.size / 200_000)
            val res = FloatArray(img.data.size / step + 1)
            var n = 0
            var i = 0
            while (i < img.data.size) {
                if ((mask == null || mask[i]) && !img.data[i].isNaN()) {
                    val x = i % img.width
                    val y = i / img.width
                    res[n++] = img.data[i] - bg.at(x, y)
                }
                i += step
            }
            val sigma = Stats.clipped(res, n, 3f, 3).sigma
            return Background(img.width, img.height, img.width / gx, gx, gy, grid, sigma, global)
        }
    }
}
