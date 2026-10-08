package org.starbridge.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starbridge.core.backlash.MotionSensor
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import org.starbridge.core.sim.SimulatedMotionSensor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Backlash measured by hand: arrows on one phone, the Android's gyroscope on the tube. */
class SlackMeterTest {
    private val t0 = 1_791_406_800_000L

    private class Rig(val sim: SimulatedHandController, val hub: SessionHub, val received: MutableList<String>)

    private suspend fun TestScope.rig(slack: Double, sensor: ((SimulatedHandController) -> MotionSensor)? = null): Rig {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(clock = clock, backlashDeg = slack, aligned = false)
        val hub = SessionHub(backgroundScope, Catalog.loadDefault(), clock, monotonic = { testScheduler.currentTime })
        hub.motionSensor = sensor?.invoke(sim) ?: SimulatedMotionSensor(sim, clock)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }
        return Rig(sim, hub, received)
    }

    /** Holds an arrow like the web page does (heartbeats every 200 ms), then rests. */
    private suspend fun TestScope.hold(hub: SessionHub, axis: String, dir: String, ms: Long, rate: Int = 6) {
        hub.onMessage("phone", """{"type":"slew","axis":"$axis","dir":"$dir","rate":$rate}""")
        var t = 0L
        while (t < ms) {
            advanceTimeBy(200); runCurrent()
            hub.onMessage("phone", """{"type":"hold","axis":"$axis"}""")
            t += 200
        }
        hub.onMessage("phone", """{"type":"stop","axis":"$axis"}""")
        advanceTimeBy(1_000); runCurrent()
    }

    private fun lastMeter(received: List<String>): JsonObject =
        Json.parseToJsonElement(received.last { it.contains("\"type\":\"slackMeter\"") }).jsonObject

    @Test
    fun reversalsMeasureTheGearSlack() = runTest {
        val (sim, hub, received) = rig(slack = 0.6).let { Triple(it.sim, it.hub, it.received) }
        hub.onMessage("phone", """{"type":"slackMeter","on":true}""")
        advanceTimeBy(1_000); runCurrent() // the tube at rest: noise baseline

        hold(hub, "azm", "pos", 3_000) // first move: loads the gears on one side
        hold(hub, "azm", "neg", 3_000) // reversal
        hold(hub, "azm", "pos", 3_000) // reversal
        hold(hub, "azm", "neg", 3_000) // reversal
        hold(hub, "azm", "neg", 2_000) // same direction: measures the lag only

        val azm = lastMeter(received)["axes"]!!.jsonObject["azm"]!!.jsonObject
        val measured = azm["slackDeg"]!!.jsonPrimitive.double
        assertEquals(3, azm["reversals"]!!.jsonPrimitive.int)
        assertTrue(abs(measured - 0.6) < 0.06, "measured slack $measured° (true 0.6°)")
        val kinds = azm["items"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
        assertEquals(listOf("first", "reversal", "reversal", "reversal", "same"), kinds)

        hub.onMessage("phone", """{"type":"slackMeterSave","axis":"azm"}""")
        assertTrue(abs(hub.brain.slack(Axis.AZM) - measured) < 1e-6, "saved for GoTo and tracking")
        assertEquals(0.0, sim.azm.rateDegPerSec, "the axis is stopped after each release")

        hub.onMessage("phone", """{"type":"slackMeter","on":false}""")
        advanceTimeBy(500); runCurrent()
        assertEquals(false, lastMeter(received)["active"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun altitudeAndAzimuthAreKeptApart() = runTest {
        val (_, hub, received) = rig(slack = 0.4).let { Triple(it.sim, it.hub, it.received) }
        hub.onMessage("phone", """{"type":"slackMeter","on":true}""")
        advanceTimeBy(1_000); runCurrent()
        hold(hub, "alt", "pos", 2_500)
        hold(hub, "alt", "neg", 2_500)
        hold(hub, "alt", "pos", 2_500)
        val axes = lastMeter(received)["axes"]!!.jsonObject
        val alt = axes["alt"]!!.jsonObject["slackDeg"]!!.jsonPrimitive.double
        assertTrue(abs(alt - 0.4) < 0.06, "altitude slack $alt°")
        assertEquals(0, axes["azm"]!!.jsonObject["reversals"]!!.jsonPrimitive.int)
    }

    @Test
    fun aQuickTapReleasedDuringTheEncoderReadNeverStartsTheMotor() = runTest {
        val (sim, hub, _) = rig(slack = 0.6).let { Triple(it.sim, it.hub, it.received) }
        hub.onMessage("phone", """{"type":"slackMeter","on":true}""")
        advanceTimeBy(1_000); runCurrent()
        // The web page sends slew and, a few ms later, stop (STOP-like messages run at once).
        val press = launch { hub.onMessage("phone", """{"type":"slew","axis":"azm","dir":"pos","rate":6}""") }
        val release = launch { hub.onMessage("phone", """{"type":"stop","axis":"azm"}""") }
        runCurrent()
        press.join(); release.join()
        advanceTimeBy(300); runCurrent()
        assertEquals(0.0, sim.azm.rateDegPerSec, "the released tap must not leave the motor running")
    }

    @Test
    fun phoneNotOnTheTubeIsReported() = runTest {
        val still = { _: SimulatedHandController ->
            object : MotionSensor {
                override fun angularSpeed(axis: Axis) = 0.001
                override val noiseFloor = 0.002
            }
        }
        val (_, hub, received) = rig(slack = 0.6, sensor = still).let { Triple(it.sim, it.hub, it.received) }
        hub.onMessage("phone", """{"type":"slackMeter","on":true}""")
        advanceTimeBy(1_000); runCurrent()
        hub.onMessage("phone", """{"type":"slew","axis":"azm","dir":"pos","rate":7}""")
        repeat(30) { advanceTimeBy(200); runCurrent(); hub.onMessage("phone", """{"type":"hold","axis":"azm"}""") }
        val live = lastMeter(received)["live"]!!.jsonObject
        assertEquals("lost", live["phase"]!!.jsonPrimitive.content)
        hub.onMessage("phone", """{"type":"stop","axis":"azm"}""")
    }

    @Test
    fun withoutGyroscopeTheModeIsRefused() = runTest {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(clock = clock, aligned = false)
        val hub = SessionHub(backgroundScope, Catalog.loadDefault(), clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }
        hub.onMessage("phone", """{"type":"slackMeter","on":true}""")
        assertTrue(received.last().contains("giroscopio"))
    }
}
