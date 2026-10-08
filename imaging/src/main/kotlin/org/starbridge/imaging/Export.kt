package org.starbridge.imaging

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writers for the stack: FITS (32-bit float, linear, for Siril/PixInsight), 16-bit TIFF
 * (linear, for Lightroom/Photoshop) and 16-bit PNG.
 */
object Export {
    /** FITS with BITPIX = −32. Colour frames are written as a 3-plane cube (NAXIS3 = 3). */
    fun fits(frame: Frame, header: Map<String, String>): ByteArray {
        val w = frame.width
        val h = frame.height
        val planes = frame.channels.size
        val cards = ArrayList<String>()
        fun card(key: String, value: String, comment: String = "") {
            val k = key.uppercase().take(8).padEnd(8)
            val line = if (key == "END") "END" else "$k= ${value.padStart(20)}" + if (comment.isNotEmpty()) " / $comment" else ""
            cards.add(line.take(80).padEnd(80))
        }
        card("SIMPLE", "T", "StarBridge live stack")
        card("BITPIX", "-32")
        card("NAXIS", if (planes == 1) "2" else "3")
        card("NAXIS1", "$w")
        card("NAXIS2", "$h")
        if (planes > 1) card("NAXIS3", "$planes")
        for ((k, v) in header) {
            val isNum = v.toDoubleOrNull() != null
            card(k, if (isNum) v else "'" + v.replace("'", "''").take(66) + "'")
        }
        card("END", "")
        val out = ByteArrayOutputStream()
        val headerBytes = cards.joinToString("").toByteArray(Charsets.US_ASCII)
        out.write(headerBytes)
        pad(out, headerBytes.size, ' '.code.toByte())
        val data = DataOutputStream(out)
        var written = 0
        for (p in 0 until planes) {
            val d = frame.channels[p].data
            // FITS rows go bottom-up.
            for (y in h - 1 downTo 0) for (x in 0 until w) {
                val v = d[y * w + x]
                data.writeFloat(if (v.isNaN()) 0f else v)
                written += 4
            }
        }
        data.flush()
        pad(out, written, 0)
        return out.toByteArray()
    }

    private fun pad(out: ByteArrayOutputStream, size: Int, fill: Byte) {
        val rem = size % 2880
        if (rem != 0) repeat(2880 - rem) { out.write(fill.toInt()) }
    }

    /** 16-bit PNG of an already stretched picture (0..1 floats per channel). */
    fun png16(width: Int, height: Int, channels: List<FloatArray>): ByteArray {
        val color = channels.size == 3
        val bytesPerPixel = if (color) 6 else 2
        val raw = ByteArray(height * (1 + width * bytesPerPixel))
        var o = 0
        for (y in 0 until height) {
            raw[o++] = 0 // filter: none
            for (x in 0 until width) {
                for (c in channels) {
                    val v = (c[y * width + x].coerceIn(0f, 1f) * 65535f).toInt()
                    raw[o++] = (v shr 8).toByte(); raw[o++] = v.toByte()
                }
            }
        }
        val deflater = Deflater(6)
        deflater.setInput(raw)
        deflater.finish()
        val comp = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) comp.write(buf, 0, deflater.deflate(buf))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(width); writeInt(height); writeByte(16); writeByte(if (color) 2 else 0)
                writeByte(0); writeByte(0); writeByte(0)
            }
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", comp.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * Uncompressed 16-bit TIFF (little endian, one strip), readable by Lightroom, Photoshop,
     * GIMP, Siril… Values are 0..1 floats per channel (1 = mono, 3 = RGB).
     */
    fun tiff16(width: Int, height: Int, channels: List<FloatArray>): ByteArray {
        val spp = channels.size
        require(spp == 1 || spp == 3) { "1 or 3 channels" }
        val dataSize = width * height * spp * 2
        val entries = 10
        val ifdOffset = 8
        val ifdSize = 2 + entries * 12 + 4
        val bpsOffset = ifdOffset + ifdSize
        val dataOffset = bpsOffset + 8
        val buf = java.nio.ByteBuffer.allocate(dataOffset + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset)
        buf.putShort(entries.toShort())
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            buf.putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
            if (type == 3 && count == 1) buf.putShort(value.toShort()).putShort(0) else buf.putInt(value)
        }
        val short = 3
        val long = 4
        entry(256, long, 1, width)
        entry(257, long, 1, height)
        if (spp == 1) entry(258, short, 1, 16) else entry(258, short, 3, bpsOffset)
        entry(259, short, 1, 1) // no compression
        entry(262, short, 1, if (spp == 1) 1 else 2) // BlackIsZero / RGB
        entry(273, long, 1, dataOffset)
        entry(277, short, 1, spp)
        entry(278, long, 1, height)
        entry(279, long, 1, dataSize)
        entry(284, short, 1, 1) // chunky
        buf.putInt(0) // no next IFD
        buf.putShort(16).putShort(16).putShort(16).putShort(0)
        for (i in 0 until width * height) for (c in channels) {
            val v = c[i]
            buf.putShort(((if (v.isNaN()) 0f else v.coerceIn(0f, 1f)) * 65535f + 0.5f).toInt().toShort())
        }
        return buf.array()
    }

    /**
     * Linear (unstretched) 0..1 version of the stack for 16-bit export: cropped to the
     * covered area, black point just under the sky background, white point at the brightest
     * pixel. Nothing is clipped except the deepest noise, so it can be processed freely.
     */
    fun linearUnit(stack: Frame, coverage: FloatArray?, mask: Mask?): Pair<Frame, List<FloatArray>> {
        val f = Renderer.crop(stack, coverage, mask)
        val lum = f.luminance
        val sample = Stats.sample(lum, null, 200_000)
        val r = Stats.clipped(sample, sample.size)
        val black = r.median - 5 * r.sigma
        var white = black + 1e-6f
        for (c in f.channels) for (v in c.data) if (!v.isNaN() && v > white) white = v
        val span = white - black
        return f to f.channels.map { c -> FloatArray(c.data.size) { i -> val v = c.data[i]; if (v.isNaN()) 0f else ((v - black) / span).coerceIn(0f, 1f) } }
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        d.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.write(t); d.write(data)
        val crc = CRC32()
        crc.update(t); crc.update(data)
        d.writeInt(crc.value.toInt())
        d.flush()
    }
}
