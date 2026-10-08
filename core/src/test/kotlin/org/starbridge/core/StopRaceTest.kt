package org.starbridge.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.MountDriver
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Slow position reads, so a STOP can land in the middle of a tracking tick or a GoTo. */
private class SlowReads(private val d: MountDriver) : MountDriver by d {
    var onRead: (() -> Unit)? = null
    override suspend fun getAltAz(): AltAz {
        onRead?.let { onRead = null; it() }
        delay(200)
        return d.getAltAz()
    }
}

/** Races found in review: nothing may keep the motors running after a STOP. */
class StopRaceTest {
    private val catalog = Catalog.loadDefault()
    private val site = Site(40.4168, -3.7038)
    private val t0 = 1_791_406_800_000L

    private fun assertStopped(sim: SimulatedHandController, what: String) {
        assertEquals(0.0, sim.azm.rateDegPerSec, "$what: azimuth still moving")
        assertEquals(0.0, sim.alt.rateDegPerSec, "$what: altitude still moving")
        assertTrue(sim.azm.gotoTarget == null && sim.alt.gotoTarget == null, "$what: GoTo still running")
    }

    @Test
    fun stopDuringATrackingTick() = runTest {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, backlashDeg = 0.0, aligned = false,
            geometry = PointingParams(azOffset = -137.0, altOffset = 12.5))
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        val info = driver.connect()
        val slow = SlowReads(driver)
        hub.attachMount(slow, info)
        val brain = hub.brain
        repeat(2) {
            val s = brain.alignmentSuggestions().first()
            sim.centerOn(brain.skyOf(s.obj)); brain.addAlignmentStar(s.obj)
        }
        brain.startTracking(label = "x")
        advanceTimeBy(10_000); runCurrent()
        assertTrue(sim.azm.rateDegPerSec != 0.0 || sim.alt.rateDegPerSec != 0.0, "should be tracking")
        repeat(5) { attempt ->
            brain.startTracking(label = "x")
            advanceTimeBy(3_000); runCurrent()
            // Land the STOP at a different point of the tick each time.
            slow.onRead = { backgroundScope.launch { delay(40L * attempt); hub.onMessage("phone", """{"type":"stopAll"}""") } }
            advanceTimeBy(5_000); runCurrent()
            assertFalse(brain.tracking)
            assertStopped(sim, "attempt $attempt")
        }
        advanceTimeBy(60_000); runCurrent()
        assertStopped(sim, "a minute later")
    }

    @Test
    fun stopDuringAGoto() = runTest {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, backlashDeg = 0.3, aligned = false,
            geometry = PointingParams(azOffset = 40.0, altOffset = -6.0))
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val brain = hub.brain
        repeat(2) {
            val s = brain.alignmentSuggestions().first()
            sim.centerOn(brain.skyOf(s.obj)); brain.addAlignmentStar(s.obj)
        }
        val far = catalog.objects.first { it.messier && brain.skyOf(it).altDeg in 30.0..70.0 }
        brain.goto(far)
        advanceTimeBy(2_500); runCurrent()
        hub.onMessage("phone", """{"type":"stopAll"}""")
        advanceTimeBy(2_000); runCurrent()
        sim.truePointing()
        assertStopped(sim, "after STOP")
        advanceTimeBy(120_000); runCurrent()
        sim.truePointing()
        assertStopped(sim, "two minutes later")
        assertFalse(brain.tracking)
    }

    @Test
    fun removingTheAlignmentStopsTracking() = runTest {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, backlashDeg = 0.0, aligned = false)
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val brain = hub.brain
        val s = brain.alignmentSuggestions().first()
        sim.centerOn(brain.skyOf(s.obj)); brain.addAlignmentStar(s.obj)
        brain.startTracking(label = "x")
        advanceTimeBy(5_000); runCurrent()
        hub.onMessage("phone", """{"type":"alignRemove","label":"${s.obj.id}"}""")
        advanceTimeBy(3_000); runCurrent()
        assertFalse(brain.tracking)
        assertStopped(sim, "after removing the alignment")
    }

    @Test
    fun handControlModeStopAlsoStopsItsTracking() = runTest {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, aligned = true)
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        assertEquals(1, sim.tracking)
        assertTrue(hub.brain.tracking, "hand control was already tracking")
        hub.onConnect("phone") {}
        hub.onMessage("phone", """{"type":"stopAll"}""")
        assertEquals(0, sim.tracking, "STOP must switch off the hand control's tracking")
    }
}
