package org.starbridge.core

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualSlackTest {

    @Test
    fun userTapsWhenTheStarMovesAndSlackIsMeasured() = runTest {
        val clock = { testScheduler.currentTime + 1_791_406_800_000L }
        val sim = SimulatedHandController(clock = clock, backlashDeg = 0.6, aligned = false)
        val hub = SessionHub(backgroundScope, Catalog.loadDefault(), clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }

        hub.onMessage("phone", """{"type":"slackVisualStart","axis":"azm"}""")
        // Wait for the creeping phase, then "watch" the tube like the user would.
        var guard = 0
        while (received.none { it.contains("\"phase\":\"watching\"") } && guard++ < 200) { advanceTimeBy(100); runCurrent() }
        sim.truePointing()
        val tubeAtStart = sim.azm.tubeDeg
        guard = 0
        while (abs(sim.azm.tubeDeg - tubeAtStart) < 0.005 && guard++ < 600) {
            advanceTimeBy(50); runCurrent(); sim.truePointing()
        }
        advanceTimeBy(300); runCurrent() // human reaction time
        hub.onMessage("phone", """{"type":"slackVisualMark"}""")
        guard = 0
        while (received.none { it.contains("\"phase\":\"done\"") } && guard++ < 50) { advanceTimeBy(100); runCurrent() }

        val slack = hub.brain.slack(Axis.AZM)
        assertTrue(abs(slack - 0.6) < 0.05, "measured slack $slack")
        assertEquals(0.0, sim.azm.rateDegPerSec, "axis must be stopped after the measurement")
    }

    @Test
    fun stopCancelsTheMeasurement() = runTest {
        val clock = { testScheduler.currentTime + 1_791_406_800_000L }
        val sim = SimulatedHandController(clock = clock, aligned = false)
        val hub = SessionHub(backgroundScope, Catalog.loadDefault(), clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        hub.onConnect("phone") {}
        hub.onMessage("phone", """{"type":"slackVisualStart","axis":"alt"}""")
        advanceTimeBy(6_000); runCurrent()
        assertTrue(sim.alt.rateDegPerSec != 0.0)
        hub.onMessage("phone", """{"type":"stopAll"}""")
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0.0, sim.alt.rateDegPerSec)
        advanceTimeBy(70_000); runCurrent()
        assertEquals(0.0, sim.alt.rateDegPerSec, "nothing may restart after STOP")
    }
}
