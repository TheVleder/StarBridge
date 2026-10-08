package org.starbridge.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.MemorySettings
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import org.starbridge.core.sky.Zone
import org.starbridge.core.sky.Zones
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The part of the sky the user can see (a window): drawn once, used by every page. */
class ZonesTest {
    private fun window(az0: Double, az1: Double, alt0: Double, alt1: Double) =
        Zone("z1", "Ventana", true, listOf(AltAz(az0, alt0), AltAz(az1, alt0), AltAz(az1, alt1), AltAz(az0, alt1)))

    @Test
    fun aWindowFrameOnTheSky() {
        val w = window(170.0, 190.0, 20.0, 40.0)
        assertTrue(w.contains(AltAz(180.0, 30.0)))
        assertFalse(w.contains(AltAz(200.0, 30.0)))
        assertFalse(w.contains(AltAz(180.0, 50.0)))
        assertFalse(w.contains(AltAz(0.0, 30.0)), "the opposite side of the sky")
        // The top edge is a straight line in space: a great circle, higher in the middle (40.43°).
        assertTrue(w.contains(AltAz(180.0, 40.3)))
        assertFalse(w.contains(AltAz(180.0, 40.6)))
    }

    @Test
    fun aWindowFacingNorthAcrossAzimuthZero() {
        val w = window(350.0, 10.0, 15.0, 35.0)
        assertTrue(w.contains(AltAz(0.0, 25.0)))
        assertTrue(w.contains(AltAz(355.0, 25.0)))
        assertTrue(w.contains(AltAz(5.0, 25.0)))
        assertFalse(w.contains(AltAz(20.0, 25.0)))
        assertFalse(w.contains(AltAz(180.0, 25.0)))
    }

    @Test
    fun severalZonesAndTheirJson() {
        val zones = Zones().upsert(window(170.0, 190.0, 20.0, 40.0)).upsert(window(80.0, 100.0, 10.0, 30.0).copy(id = "z2", on = false))
        assertEquals(true, zones.visible(AltAz(180.0, 30.0)))
        assertEquals(false, zones.visible(AltAz(90.0, 20.0)), "the second zone is off")
        assertNull(Zones().visible(AltAz(90.0, 20.0)), "no zones: everything counts as visible")
        assertEquals("z3", zones.newId())
        val back = Zones.parse(zones.toJson().toString())
        assertEquals(zones.list.map { it.id to it.on }, back.list.map { it.id to it.on })
        assertEquals(true, back.visible(AltAz(180.0, 30.0)))
        assertTrue(Zones.parse("not json").list.isEmpty())
    }

    @Test
    fun sharedByEveryPage() = runTest {
        val site = Site(40.4168, -3.7038)
        val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC
        val clock = { testScheduler.currentTime + t0 }
        val tilted = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7)
        val sim = SimulatedHandController(site, clock, backlashDeg = 0.0, aligned = false, geometry = tilted)
        val catalog = Catalog.loadDefault()
        val settings = MemorySettings()
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime }, settings = settings)
        hub.brain.updateGps(site, 5.0)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        repeat(2) {
            val s = hub.brain.alignmentSuggestions().first()
            sim.centerOn(hub.brain.skyOf(s.obj))
            hub.brain.addAlignmentStar(s.obj)
        }
        val pc = mutableListOf<String>()
        val phone = mutableListOf<String>()
        hub.onConnect("pc") { pc += it }
        hub.onConnect("phone") { phone += it }
        fun TestScope.settle() { advanceTimeBy(200); runCurrent() }
        fun last(list: List<String>, type: String): JsonObject =
            Json.parseToJsonElement(list.last { it.contains("\"type\":\"$type\"") }).jsonObject

        // A window around where Vega is now, drawn on the PC.
        val vega = assertNotNull(catalog.find("Vega"))
        val v = hub.brain.skyOf(vega)
        hub.onMessage(
            "pc",
            """{"type":"setZone","zone":{"name":"Ventana del salón","points":[[${v.azDeg - 6},${v.altDeg - 6}],[${v.azDeg + 6},${v.altDeg - 6}],[${v.azDeg + 6},${v.altDeg + 6}],[${v.azDeg - 6},${v.altDeg + 6}]]}}""",
        )
        settle()
        val seen = last(phone, "zones")["zones"]!!.jsonArray
        assertEquals("z1", seen.single().jsonObject["id"]!!.jsonPrimitive.content, "the phone gets it at once")
        assertTrue(settings.get(SessionHub.KEY_ZONES)!!.contains("Ventana del salón"), "kept for the next session")

        // Lists say what is inside.
        hub.onMessage("phone", """{"type":"search","q":"vega"}""")
        assertTrue(last(phone, "searchResult")["items"]!!.jsonArray.first().jsonObject["inZone"]!!.jsonPrimitive.boolean)
        hub.onMessage("phone", """{"type":"search","q":"m31"}""")
        assertFalse(last(phone, "searchResult")["items"]!!.jsonArray.first().jsonObject["inZone"]!!.jsonPrimitive.boolean)

        // Its night: when it is in the window.
        hub.onMessage("phone", """{"type":"objectNight","id":"Vega"}""")
        val night = last(phone, "objectNight")
        val windows = night["zoneWindows"]!!.jsonArray
        assertTrue(windows.isNotEmpty())
        val (start, end) = windows.first().jsonArray.map { it.jsonPrimitive.long }
        assertTrue(start <= clock() + 600_000 && end > start, "Vega is in the window now: $start..$end")
        assertEquals(4, night["samples"]!!.jsonArray.first().jsonArray.size)

        // A GoTo outside the window still runs, with a warning.
        hub.onMessage("pc", """{"type":"gotoAltAz","az":${(v.azDeg + 90) % 360},"alt":45}""")
        settle()
        assertTrue(pc.any { it.contains("fuera de tu zona visible") })

        // Bad zones are refused; deleting one tells everybody.
        hub.onMessage("pc", """{"type":"setZone","zone":{"points":[[10,10],[20,20]]}}""")
        assertTrue(pc.last().contains("error"))
        hub.onMessage("pc", """{"type":"deleteZone","id":"z1"}""")
        settle()
        assertTrue(last(phone, "zones")["zones"]!!.jsonArray.isEmpty())
        hub.onMessage("phone", """{"type":"search","q":"vega"}""")
        assertNull(last(phone, "searchResult")["items"]!!.jsonArray.first().jsonObject["inZone"])
    }
}
