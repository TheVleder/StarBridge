package org.starbridge.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.starbridge.core.astro.Night
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.catalog.Constellations
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the computer app adds: any speed, GoTo by coordinates, park, tracking rates, console, planner. */
class FullControlTest {
    private val catalog = Catalog.loadDefault()
    private val site = Site(40.4168, -3.7038)
    private val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC: night in Spain
    private val tilted = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7)

    private class Rig(val sim: SimulatedHandController, val hub: SessionHub, val received: MutableList<String>) {
        fun last(type: String): JsonObject =
            Json.parseToJsonElement(received.last { it.contains("\"type\":\"$type\"") }).jsonObject
    }

    private suspend fun TestScope.rig(aligned: Boolean = true, start: Long = t0): Rig {
        val clock = { testScheduler.currentTime + start }
        val sim = SimulatedHandController(site, clock, backlashDeg = 0.0, aligned = false, geometry = tilted)
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        hub.brain.updateGps(site, 5.0)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val received = mutableListOf<String>()
        hub.onConnect("pc") { received += it }
        if (aligned) repeat(2) {
            val s = hub.brain.alignmentSuggestions().first()
            sim.centerOn(hub.brain.skyOf(s.obj))
            hub.brain.addAlignmentStar(s.obj)
        }
        return Rig(sim, hub, received)
    }

    private fun TestScope.waitUntil(maxMs: Long, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < maxMs) {
            advanceTimeBy(500); runCurrent(); waited += 500
        }
        assertTrue(cond(), "condition not met after $maxMs ms")
    }

    @Test
    fun anySpeedFromTheSlider() = runTest {
        val r = rig(aligned = false)
        r.hub.onMessage("pc", """{"type":"slew","axis":"azm","dir":"pos","arcsec":3600}""")
        advanceTimeBy(200); runCurrent()
        assertEquals(1.0, r.sim.azm.rateDegPerSec, 1e-6, "3600″/s = 1°/s")
        r.hub.onMessage("pc", """{"type":"stop","axis":"azm"}""")
        advanceTimeBy(200); runCurrent()
        assertEquals(0.0, r.sim.azm.rateDegPerSec)
        // The top of the slider is the motors' real top speed: the hand control's rate 9.
        r.hub.onMessage("pc", """{"type":"slew","axis":"azm","dir":"pos","arcsec":16000}""")
        advanceTimeBy(200); runCurrent()
        assertEquals(SimulatedHandController.FIXED_RATES[9], r.sim.azm.rateDegPerSec, 1e-6)
        r.hub.onMessage("pc", """{"type":"stop","axis":"azm"}""")
        advanceTimeBy(200); runCurrent()
    }

    @Test
    fun gotoTypedCoordinatesTracksThem() = runTest {
        val r = rig()
        val m31 = assertNotNull(catalog.find("M31")?.fixed)
        r.hub.onMessage("pc", """{"type":"gotoCoords","ra":${m31.raHours},"dec":${m31.decDeg},"label":"Mi objetivo"}""")
        waitUntil(240_000) { r.hub.brain.tracking }
        assertEquals("Mi objetivo", r.hub.brain.trackingLabel)
        val err = PointingParams.separation(r.sim.truePointing(), r.hub.brain.skyOf(assertNotNull(catalog.find("M31"))))
        assertTrue(err < 0.06, "error $err°")
    }

    @Test
    fun gotoAFixedDirectionDoesNotTrack() = runTest {
        val r = rig()
        r.hub.onMessage("pc", """{"type":"gotoAltAz","az":120,"alt":45}""")
        waitUntil(240_000) { !r.hub.brain.gotoActive && r.received.any { it.contains("Llegado") } }
        assertFalse(r.hub.brain.tracking)
        val err = PointingParams.separation(r.sim.truePointing(), AltAz(120.0, 45.0))
        assertTrue(err < 0.06, "error $err°")
    }

    @Test
    fun parkGoesBackToThePowerOnPosition() = runTest {
        val r = rig()
        r.hub.onMessage("pc", """{"type":"park"}""")
        waitUntil(240_000) { r.received.any { it.contains("Aparcado") } }
        r.sim.truePointing()
        assertTrue(abs(PointingParams.angleDiff(r.sim.azm.motorDeg, 0.0)) < 0.03, "az encoder ${r.sim.azm.motorDeg}")
        assertTrue(abs(r.sim.alt.motorDeg) < 0.03, "alt encoder ${r.sim.alt.motorDeg}")
        assertFalse(r.hub.brain.tracking)
    }

    @Test
    fun lunarRateCreepsEastAmongTheStars() = runTest {
        val r = rig()
        r.hub.onMessage("pc", """{"type":"track","on":true,"rate":"lunar"}""")
        advanceTimeBy(2_000); runCurrent()
        val start = assertNotNull(r.hub.brain.pointing().raDec)
        advanceTimeBy(600_000); runCurrent() // 10 min
        val end = assertNotNull(r.hub.brain.pointing().raDec)
        val driftHours = end.raHours - start.raHours
        // The Moon gains ~0.0366 h of RA per hour: ~0.0061 h in 10 minutes.
        assertTrue(abs(driftHours - 0.0061) < 0.0015, "RA drift $driftHours h in 10 min")
        assertEquals("lunar", r.last("status")["trackRate"]?.jsonPrimitive?.content)
        assertNotNull(r.last("status")["track"], "live tracking data for the charts")
    }

    @Test
    fun consoleCommandsAndTheirSafety() = runTest {
        val r = rig(aligned = false)
        r.hub.onMessage("pc", """{"type":"raw","bytes":[75,120],"response":1}""")
        assertEquals("x", r.last("raw")["text"]?.jsonPrimitive?.content)
        // A slew typed by hand must be confirmed.
        r.hub.onMessage("pc", """{"type":"raw","bytes":[80,2,16,36,6,0,0,0],"response":0}""")
        assertTrue(r.received.last().contains("confírmala"))
        assertEquals(0.0, r.sim.azm.rateDegPerSec)
        r.hub.onMessage("pc", """{"type":"raw","bytes":[80,2,16,36,6,0,0,0],"response":0,"confirm":true}""")
        assertTrue(r.sim.azm.rateDegPerSec > 0)
        r.hub.onMessage("pc", """{"type":"stopAll"}""")
        advanceTimeBy(500); runCurrent()
        assertEquals(0.0, r.sim.azm.rateDegPerSec, "STOP stops it like anything else")
        r.hub.onMessage("pc", """{"type":"trace"}""")
        assertTrue(r.last("trace")["lines"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun alignmentCentredAgainstTheGotoIsCaught() = runTest {
        // Real slack, and the wrong value in the settings: only centring like the GoTo arrives is safe.
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, backlashDeg = 1.0, aligned = false, geometry = tilted)
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        hub.brain.updateGps(site, 5.0)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val received = mutableListOf<String>()
        hub.onConnect("pc") { received += it }
        fun settle() { advanceTimeBy(1_500); runCurrent() }
        val first = hub.brain.alignmentSuggestions().first().obj
        val (dAz, dAlt) = hub.brain.arrivalDirections(first)
        // Centred finishing the way the GoTo arrives on both axes...
        sim.centerOn(hub.brain.skyOf(first), fromAz = -dAz, fromAlt = -dAlt); settle()
        sim.centerOn(hub.brain.skyOf(first), fromAz = dAz, fromAlt = dAlt); settle()
        // ...then a last touch the other way in azimuth.
        sim.centerOn(hub.brain.skyOf(first), fromAz = -dAz, fromAlt = dAlt); settle()
        hub.onMessage("pc", """{"type":"alignAdd","id":"${first.id}","checkFinish":true}""")
        val finish = Json.parseToJsonElement(received.last()).jsonObject
        assertEquals("alignFinish", finish["type"]?.jsonPrimitive?.content)
        assertEquals(dAz.toString(), finish["azm"]?.jsonPrimitive?.content, "finish azimuth the way the GoTo arrives")
        assertEquals(null, finish["alt"], "altitude was fine")
        assertEquals(null, hub.brain.solution, "nothing added")
        // Finished the right way: added. Phones (no checkFinish) are never stopped.
        sim.centerOn(hub.brain.skyOf(first), fromAz = dAz, fromAlt = dAlt); settle()
        hub.onMessage("pc", """{"type":"alignAdd","id":"${first.id}","checkFinish":true}""")
        assertEquals(1, hub.brain.solution?.starCount)
        val second = hub.brain.alignmentSuggestions().first().obj
        sim.centerOn(hub.brain.skyOf(second), fromAz = -dAz, fromAlt = -dAlt); settle()
        hub.onMessage("pc", """{"type":"alignAdd","id":"${second.id}"}""")
        assertEquals(2, hub.brain.solution?.starCount)
    }

    @Test
    fun twoStarsTooCloseAreReported() = runTest {
        val r = rig(aligned = false)
        val capella = assertNotNull(catalog.find("Capella"))
        val menkalinan = assertNotNull(catalog.find("Menkalinan"))
        r.sim.centerOn(r.hub.brain.skyOf(menkalinan))
        r.hub.onMessage("pc", """{"type":"alignAdd","id":"Menkalinan"}""")
        r.sim.centerOn(r.hub.brain.skyOf(capella))
        r.hub.onMessage("pc", """{"type":"alignAdd","id":"Capella"}""")
        assertTrue(r.received.any { it.contains("inclinación de la base sale mal") }, r.received.last())
        assertEquals(2, r.hub.brain.solution?.starCount, "added anyway")
    }

    @Test
    fun noGotoNearTheSunInDaylight() = runTest {
        val noon = 1_791_374_400_000L // 2026-10-07 12:00 UTC
        val r = rig(aligned = true, start = noon)
        val now = noon + testScheduler.currentTime
        val sun = org.starbridge.core.astro.Astro.apparentAltAz(
            org.starbridge.core.astro.Planets.topocentric(org.starbridge.core.astro.Planets.Body.SUN, now, site), site, now,
        )
        assertTrue(sun.altDeg > 20, "the Sun is up at noon")
        r.hub.onMessage("pc", """{"type":"gotoAltAz","az":${sun.azDeg + 5},"alt":${sun.altDeg}}""")
        assertTrue(r.received.last().contains("Sol"), r.received.last())
        assertFalse(r.hub.brain.gotoActive)
    }

    @Test
    fun plannerNightAndSkyFigures() = runTest {
        val r = rig(aligned = false)
        r.hub.onMessage("pc", """{"type":"objectNight","id":"M31"}""")
        val night = r.last("objectNight")
        assertTrue(night["samples"]!!.jsonArray.size > 90, "a sample every 10 min for 17 h")
        val darkStart = night["darkStart"]?.jsonPrimitive?.long
        val darkEnd = night["darkEnd"]?.jsonPrimitive?.long
        assertNotNull(darkStart); assertNotNull(darkEnd)
        assertTrue(darkEnd > darkStart)
        val info = Night.of(assertNotNull(catalog.find("M31")), site, t0)
        assertTrue(info.maxAltDeg in 60.0..90.0, "M31 culminates high from Madrid in October: ${info.maxAltDeg}")

        r.hub.onMessage("pc", """{"type":"sky","lines":true}""")
        val sky = r.last("sky")
        assertTrue(sky["lines"]!!.jsonArray.size > 30, "constellation figures above the horizon")
        assertTrue(sky["labels"]!!.jsonArray.any { it.jsonArray[0].jsonPrimitive.content == "Casiopea" })
        assertEquals(88, Constellations.loadDefault().labels.size)
    }
}
