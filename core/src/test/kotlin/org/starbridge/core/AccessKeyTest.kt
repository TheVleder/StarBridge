package org.starbridge.core

import kotlinx.coroutines.test.runTest
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.server.MemorySettings
import org.starbridge.core.server.SessionHub
import org.starbridge.core.share.QrCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccessKeyTest {
    @Test
    fun qrUsesTheWifiAddressNotWifiDirectOrVpn() {
        val pick = org.starbridge.core.share.LanAddress::pick
        // Huawei with WiFi Direct up and a VPN: the iPhone hotspot address must win.
        assertEquals("172.20.10.3", pick(listOf("p2p0" to "192.168.49.1", "tun0" to "10.8.0.2", "wlan0" to "172.20.10.3")))
        // This phone hosts the hotspot.
        assertEquals("192.168.43.1", pick(listOf("rmnet_data0" to "10.12.0.5", "ap0" to "192.168.43.1")))
        // Only unreachable interfaces: better no QR than a wrong one.
        assertEquals(null, pick(listOf("p2p-wlan0-0" to "192.168.49.1")))
        // Unknown names are still usable.
        assertEquals("192.168.1.20", pick(listOf("mlan0" to "192.168.1.20")))
    }

    private val catalog = Catalog.loadDefault()

    @Test
    fun keyIsRandomPersistentAndChecked() = runTest {
        val settings = MemorySettings()
        val hub = SessionHub(backgroundScope, catalog, settings = settings)
        val key = hub.accessKey
        assertEquals(12, key.length)
        assertTrue(hub.checkKey(key))
        assertFalse(hub.checkKey(null))
        assertFalse(hub.checkKey(""))
        assertFalse(hub.checkKey(key.dropLast(1) + "x".takeIf { key.last() != 'x' }.orEmpty().ifEmpty { "y" }))
        // Same key after a restart (stored in the settings)…
        assertEquals(key, SessionHub(backgroundScope, catalog, settings = settings).accessKey)
        // …and different for another phone.
        assertNotEquals(key, SessionHub(backgroundScope, catalog, settings = MemorySettings()).accessKey)
        assertEquals("http://172.20.10.3:8080/?k=$key", hub.shareUrl("http://172.20.10.3:8080"))
        // Typed by hand with dashes, spaces or capitals.
        assertEquals(14, hub.displayKey.length)
        assertTrue(hub.checkKey(hub.displayKey))
        assertTrue(hub.checkKey(hub.displayKey.uppercase()))
        assertTrue(hub.checkKey(hub.displayKey.replace("-", " ")))
    }

    @Test
    fun rotatingTheKeyDisconnectsEveryone() = runTest {
        val hub = SessionHub(backgroundScope, catalog)
        val old = hub.accessKey
        var closed = 0
        hub.onConnect("iphone", send = {}, close = { closed++ })
        hub.onConnect("friend", send = {}, close = { closed++ })
        val new = hub.rotateKey()
        assertNotEquals(old, new)
        assertEquals(2, closed)
        assertFalse(hub.checkKey(old))
        assertTrue(hub.checkKey(new))
    }

    @Test
    fun qrSvgIsWellFormed() {
        val svg = QrCode.svg("http://172.20.10.3:8080/?k=abcdefghjkmn")
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"))
        val m = QrCode.matrix("http://172.20.10.3:8080/?k=abcdefghjkmn")
        assertTrue(m.size >= 25 && m.all { it.size == m.size })
        // Finder pattern: top-left 7x7 dark border after the 2-module quiet zone.
        assertTrue((2..8).all { m[2][it] && m[it][2] })
    }

    @Test
    fun qrDecodesBackToTheLink() {
        val link = "http://172.20.10.3:8080/?k=j576xmkfmvu2"
        val m = QrCode.matrix(link)
        val scale = 4
        val n = m.size * scale
        val pixels = IntArray(n * n) { i -> if (m[(i / n) / scale][(i % n) / scale]) 0xFF000000.toInt() else -1 }
        val source = com.google.zxing.RGBLuminanceSource(n, n, pixels)
        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        assertEquals(link, com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text)
    }
}
