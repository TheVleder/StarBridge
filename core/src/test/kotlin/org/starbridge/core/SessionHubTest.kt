package org.starbridge.core

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionHubTest {
    private fun type(s: String) = Json.parseToJsonElement(s).jsonObject["type"]?.jsonPrimitive?.content

    @Test
    fun remoteCameraIsSeenAndDrivenFromAnyPage() = runTest {
        val hub = SessionHub(backgroundScope, Catalog.loadDefault())
        val phone = mutableListOf<String>()
        val pc = mutableListOf<String>()
        hub.onConnect("iphone") { phone += it }
        hub.onConnect("pc") { pc += it }
        // no camera yet: a command gets a clear error
        hub.onMessage("pc", """{"type":"camCmd","cmd":"start"}""")
        assertTrue(pc.last().contains("no está conectada"))
        // the iPhone reports its state: every page gets it, marked online
        hub.onMessage("iphone", """{"type":"camStatus","source":"iphone","activity":"idle","stacked":0}""")
        assertTrue(pc.last { type(it) == "camStatus" }.contains("\"online\":true"))
        // a page drives it: the order reaches the iPhone unchanged
        hub.onMessage("pc", """{"type":"camCmd","source":"iphone","cmd":"start"}""")
        assertTrue(phone.last().contains("\"cmd\":\"start\""))
        // a page that connects later sees the camera at once
        val late = mutableListOf<String>()
        hub.onConnect("late") { late += it }
        assertTrue(late.any { type(it) == "camStatus" })
        // notices reach every page
        hub.onMessage("iphone", """{"type":"camNotice","text":"Hola"}""")
        assertTrue(pc.last().contains("Hola"))
        // the iPhone leaves: offline for everyone, and commands fail again
        hub.onDisconnect("iphone")
        assertTrue(pc.last { type(it) == "camStatus" }.contains("\"online\":false"))
        hub.onMessage("pc", """{"type":"camCmd","cmd":"pause"}""")
        assertTrue(pc.last().contains("no está conectada"))
    }

    @Test
    fun fullWebFlow() = runTest {
        val sim = SimulatedHandController(clock = { testScheduler.currentTime + 1_760_000_000_000L })
        val hub = SessionHub(backgroundScope, Catalog.loadDefault(), clock = { testScheduler.currentTime + 1_760_000_000_000L }, monotonic = { testScheduler.currentTime })
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }
        assertEquals("info", type(received.first()))

        // Without a telescope every command answers with a clear error.
        hub.onMessage("phone", """{"type":"slew","axis":"azm","dir":"pos","rate":5}""")
        assertTrue(received.last().contains("no conectado"))

        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())

        hub.onMessage("phone", """{"type":"search","q":"m42"}""")
        val result = received.last { type(it) == "searchResult" }
        val first = Json.parseToJsonElement(result).jsonObject["items"]!!.jsonArray.first().jsonObject
        assertEquals("M42", first["id"]!!.jsonPrimitive.content)

        // "Todas" finds the object whatever its type; "NGC / IC" lists only NGC/IC objects.
        hub.onMessage("phone", """{"type":"search","q":"m31","category":"all"}""")
        val all = Json.parseToJsonElement(received.last { type(it) == "searchResult" }).jsonObject["items"]!!.jsonArray
        assertEquals("M31", all.first().jsonObject["id"]!!.jsonPrimitive.content)
        hub.onMessage("phone", """{"type":"search","q":"","category":"ngc"}""")
        val ngc = Json.parseToJsonElement(received.last { type(it) == "searchResult" }).jsonObject["items"]!!.jsonArray
        assertTrue(ngc.isNotEmpty())
        assertTrue(ngc.all { it.jsonObject["id"]!!.jsonPrimitive.content.let { id -> id.startsWith("NGC") || id.startsWith("IC") } })

        // Hold-to-move: slew then no heartbeat → watchdog stops the axis.
        hub.onMessage("phone", """{"type":"slew","axis":"azm","dir":"pos","rate":9}""")
        runCurrent()
        assertTrue(sim.azm.rateDegPerSec > 0)
        advanceTimeBy(1200)
        runCurrent()
        assertEquals(0.0, sim.azm.rateDegPerSec)

        // Backlash read/write through the web protocol.
        hub.onMessage("phone", """{"type":"setBacklash","axis":"alt","pos":30,"neg":25}""")
        assertTrue(received.last { type(it) == "backlash" }.contains("\"alt\":{\"pos\":30,\"neg\":25}"))

        // Disconnecting the phone while moving stops the mount.
        hub.onMessage("phone", """{"type":"slew","axis":"alt","dir":"neg","rate":5}""")
        hub.onDisconnect("phone")
        assertEquals(0.0, sim.alt.rateDegPerSec)
    }
}
