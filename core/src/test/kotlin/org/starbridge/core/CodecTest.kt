package org.starbridge.core

import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.Direction
import org.starbridge.core.protocol.NexStarCodec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecTest {
    private fun close(a: Double, b: Double, eps: Double = 1e-4) = assertTrue(abs(a - b) < eps, "$a != $b")

    @Test
    fun officialExamples() {
        // Celestron doc: 12CE → 26.4441°, 12AB0500 → 26.252°
        close(NexStarCodec.hexToDeg("12CE"), 26.4441)
        close(NexStarCodec.hexToDeg("12AB0500"), 26.252, 1e-3)
    }

    @Test
    fun hexRoundTripAndNegativeDec() {
        for (deg in listOf(0.0, 10.5, 123.456, 359.9, -10.0, -89.5)) {
            val back = NexStarCodec.signedDeg(NexStarCodec.hexToDeg(NexStarCodec.degToHex32(deg)))
            close(back, if (deg > 180) deg - 360 else deg)
        }
        assertEquals(8, NexStarCodec.degToHex32(12.0).length)
        assertTrue(NexStarCodec.degToHex32(12.0).endsWith("00"))
    }

    @Test
    fun slewBytesMatchOfficialDoc() {
        val c = NexStarCodec.slewFixed(Axis.AZM, Direction.POSITIVE, 5)
        assertContentEquals(byteArrayOf('P'.code.toByte(), 2, 16, 36, 5, 0, 0, 0), c.bytes)
        val n = NexStarCodec.slewFixed(Axis.ALT, Direction.NEGATIVE, 9)
        assertContentEquals(byteArrayOf('P'.code.toByte(), 2, 17, 37, 9, 0, 0, 0), n.bytes)
    }

    @Test
    fun variableRateMatchesOfficialExample() {
        // 150 arcsec/s → high 2, low 88
        val c = NexStarCodec.slewVariable(Axis.AZM, Direction.POSITIVE, 150.0)
        assertContentEquals(byteArrayOf('P'.code.toByte(), 3, 16, 6, 2, 88, 0, 0), c.bytes)
    }

    @Test
    fun getDeviceVersionMatchesOfficialDoc() {
        val c = NexStarCodec.passThrough(16, NexStarCodec.MC_GET_VER, ByteArray(0), 2)
        assertContentEquals(byteArrayOf('P'.code.toByte(), 1, 16, 0xFE.toByte(), 0, 0, 0, 2), c.bytes)
    }

    @Test
    fun locationRoundTrip() {
        // Celestron doc example: 33°50'41" N, 118°20'17" W
        val enc = NexStarCodec.encodeLocation(33 + 50 / 60.0 + 41 / 3600.0, -(118 + 20 / 60.0 + 17 / 3600.0))
        assertContentEquals(byteArrayOf(33, 50, 41, 0, 118, 20, 17, 1), enc)
        val (lat, lon) = NexStarCodec.decodeLocation(byteArrayOf(33, 50, 41, 0, 118, 20, 17, 1))
        close(lat, 33.8447, 1e-3)
        close(lon, -118.3381, 1e-3)
    }
}
