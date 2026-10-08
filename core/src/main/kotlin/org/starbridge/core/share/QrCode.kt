package org.starbridge.core.share

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes for "scan to open the controls on another phone". */
object QrCode {
    /** Module grid (true = dark), including a 2-module quiet zone. */
    fun matrix(text: String): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits.get(x, y) } }
    }

    /**
     * Crisp, scalable SVG. Dark modules on a light background: phone cameras need the
     * contrast, so it stays black on white even in the red night mode.
     */
    fun svg(text: String): String {
        val m = matrix(text)
        val n = m.size
        val path = StringBuilder()
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!m[y][x]) { x++; continue }
                val start = x
                while (x < n && m[y][x]) x++
                path.append("M$start ${y}h${x - start}v1h-${x - start}z")
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $n $n" shape-rendering="crispEdges">""" +
            """<rect width="$n" height="$n" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }
}
