package org.starbridge.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopTest {
    @Test
    fun picksTheUsbSerialCableAmongOtherPorts() {
        val ports = listOf(
            PortInfo("COM1", "Communications Port", -1),
            PortInfo("COM3", "Prolific PL2303GT USB Serial COM Port", 0x067B),
            PortInfo("COM4", "Standard Serial over Bluetooth link", -1),
        )
        assertEquals("COM3", pickPort(ports, null)?.name)
        assertEquals("COM4", pickPort(ports, "com4")?.name, "the one asked for wins")
        assertNull(pickPort(ports, "COM9"), "asked for a port that is not there")
        assertEquals("COM1", pickPort(ports.take(1), null)?.name, "a single port is used whatever it is")
        assertNull(
            pickPort(listOf(PortInfo("COM1", "Communications Port", -1), PortInfo("COM2", "Communications Port", -1)), null),
            "two unknown ports: better ask",
        )
    }

    @Test
    fun settingsSurviveARestart() {
        val file = File.createTempFile("starbridge", ".properties").apply { delete() }
        try {
            FileSettings(file).apply {
                put("accessKey", "abcd1234efgh")
                put("slackAz", "0.6")
            }
            val again = FileSettings(file)
            assertEquals("abcd1234efgh", again.get("accessKey"))
            assertEquals("0.6", again.get("slackAz"))
            assertNull(again.get("nope"))
        } finally {
            file.delete()
        }
    }
}
