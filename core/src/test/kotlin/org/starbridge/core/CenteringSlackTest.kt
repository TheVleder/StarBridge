package org.starbridge.core

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starbridge.core.astro.Site
import org.starbridge.core.backlash.CenteringSlack
import org.starbridge.core.backlash.CenteringSlack.Mark
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slack from centring a star twice with opposite last touches: exact, whatever the reaction time. */
class CenteringSlackTest {
    @Test
    fun theStarsOwnMotionIsTakenOut() {
        val c = CenteringSlack("Vega")
        c.add(Mark(AltAz(100.8, 30.4), AltAz(100.0, 30.0), lastAz = 1, lastAlt = 1, at = 0))
        c.add(Mark(AltAz(99.3, 29.2), AltAz(100.1, 29.8), lastAz = -1, lastAlt = -1, at = 60_000))
        // Azimuth: encoders 1.5° apart, but the star moved 0.1° the other way: 1.6°.
        assertEquals(1.6, assertNotNull(c.estimate(Axis.AZM).slackDeg), 1e-9)
        // Altitude: encoders 1.2° apart, the star went down 0.2°: 1.0°.
        assertEquals(1.0, assertNotNull(c.estimate(Axis.ALT).slackDeg), 1e-9)
        assertEquals(1, c.next(Axis.AZM), "after a negative finish, finish positive")
        // Finishing the same way twice proves nothing.
        c.add(Mark(AltAz(99.0, 29.0), AltAz(100.2, 29.6), lastAz = -1, lastAlt = 1, at = 120_000))
        assertEquals(1, c.estimate(Axis.AZM).samples.size)
        assertEquals(2, c.estimate(Axis.ALT).samples.size)
        assertNull(CenteringSlack.sample(Axis.AZM, c.last!!, c.last!!))
    }

    @Test
    fun measuredOnTheMountWhileTheSkyTurns() = runTest {
        val site = Site(40.4168, -3.7038)
        val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC
        val clock = { testScheduler.currentTime + t0 }
        val trueSlack = 1.8
        val sim = SimulatedHandController(
            site, clock, backlashDeg = trueSlack, aligned = false,
            geometry = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7),
        )
        val catalog = Catalog.loadDefault()
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        hub.brain.updateGps(site, 5.0)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val received = mutableListOf<String>()
        hub.onConnect("pc") { received += it }
        fun state(): JsonObject = Json.parseToJsonElement(received.last { it.contains("\"slackCenter\"") }).jsonObject
        fun settle() { advanceTimeBy(1_500); runCurrent() }
        val vega = assertNotNull(catalog.find("Vega"))

        hub.onMessage("pc", """{"type":"slackCenterStart","id":"Vega"}""")
        // First centring, the last touches to the right and up (after coming from the other side).
        sim.centerOn(hub.brain.skyOf(vega), fromAz = -1, fromAlt = -1); settle()
        sim.centerOn(hub.brain.skyOf(vega), fromAz = 1, fromAlt = 1); settle()
        hub.onMessage("pc", """{"type":"slackCenterMark"}""")
        assertEquals("neg", if (state()["azm"]!!.jsonObject["next"]!!.jsonPrimitive.int < 0) "neg" else "pos")

        // Two minutes later (Vega has moved): overshoot and centre it again finishing left and down.
        advanceTimeBy(120_000); runCurrent()
        sim.centerOn(hub.brain.skyOf(vega), fromAz = -1, fromAlt = -1); settle()
        hub.onMessage("pc", """{"type":"slackCenterMark"}""")
        val s = state()
        val az = s["azm"]!!.jsonObject["slack"]!!.jsonPrimitive.double
        val alt = s["alt"]!!.jsonObject["slack"]!!.jsonPrimitive.double
        assertEquals(trueSlack, az, 0.03, "azimuth slack")
        assertEquals(trueSlack, alt, 0.03, "altitude slack")

        hub.onMessage("pc", """{"type":"slackCenterSave"}""")
        runCurrent()
        assertEquals(az, hub.brain.slack(Axis.AZM), 1e-3)
        assertTrue(received.any { it.contains("Holgura guardada") })
    }
}
