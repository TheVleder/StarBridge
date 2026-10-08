package org.starbridge.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.control.GotoMethod
import org.starbridge.core.control.OrientationSensor
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import org.starbridge.core.transport.SerialTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** GoTo when the hand control does not help: it ignores `b`, or a motor is wired backwards. */
class GotoFallbackTest {
    private val catalog = Catalog.loadDefault()
    private val site = Site(40.4168, -3.7038)
    private val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC
    private val tilted = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7)

    private fun TestScope.waitUntil(maxMs: Long, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < maxMs) {
            advanceTimeBy(500); runCurrent(); waited += 500
        }
        assertTrue(cond(), "condition not met after $maxMs ms")
    }

    private fun TestScope.simulator(ignoresB: Boolean = false, azReversed: Boolean = false, slack: Double = 0.6) =
        SimulatedHandController(
            site, { testScheduler.currentTime + t0 }, backlashDeg = slack, aligned = false, geometry = tilted,
            ignoresGotoUnaligned = ignoresB, azMotorReversed = azReversed,
        )

    private fun TestScope.newHub() = SessionHub(
        backgroundScope, catalog, { testScheduler.currentTime + t0 }, monotonic = { testScheduler.currentTime },
    )

    /** The real cable: every command takes a while (9600 baud + the hand control thinking). */
    private class Slow(private val inner: SerialTransport, private val ms: Long) : SerialTransport {
        override suspend fun write(data: ByteArray) { delay(ms); inner.write(data) }
        override suspend fun read(timeoutMs: Long) = inner.read(timeoutMs)
        override suspend fun clearInput() = inner.clearInput()
        override fun close() = inner.close()
    }

    /** Connected and aligned on two stars, slack known. */
    private suspend fun TestScope.ready(sim: SimulatedHandController, latencyMs: Long = 0): SessionHub {
        val hub = newHub()
        val driver = NexStarDriver(if (latencyMs > 0) Slow(sim, latencyMs) else sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        hub.brain.setSlack(Axis.AZM, sim.azm.backlashDeg)
        hub.brain.setSlack(Axis.ALT, sim.alt.backlashDeg)
        repeat(2) {
            val s = hub.brain.alignmentSuggestions().first()
            sim.centerOn(hub.brain.skyOf(s.obj))
            hub.brain.addAlignmentStar(s.obj)
        }
        return hub
    }

    private fun target(hub: SessionHub) = catalog.objects.first { it.messier && hub.brain.skyOf(it).altDeg in 30.0..70.0 }

    @Test
    fun handControlThatIgnoresGotoIsDrivenBySoftware() = runTest {
        val sim = simulator(ignoresB = true)
        val hub = ready(sim)
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }
        val obj = target(hub)
        hub.brain.goto(obj)
        waitUntil(200_000) { hub.brain.tracking }
        val err = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(obj))
        assertTrue(err < 0.06, "software GoTo error $err° on ${obj.id}")
        assertEquals(false, hub.brain.handControlGotoWorks)
        assertTrue(received.any { it.contains("StarBridge mueve los motores") }, "the user is told why")
        assertTrue(hub.diagnosticsText().contains("el mando ejecuta 'b': NO"))
    }

    @Test
    fun handControlThatWorksKeepsBeingUsed() = runTest {
        val sim = simulator()
        val hub = ready(sim)
        hub.brain.goto(target(hub))
        waitUntil(200_000) { hub.brain.tracking }
        assertEquals(true, hub.brain.handControlGotoWorks)
    }

    @Test
    fun softwareGotoLearnsAReversedMotor() = runTest {
        val sim = simulator(azReversed = true, slack = 0.0)
        val hub = ready(sim)
        hub.brain.gotoMethod = GotoMethod.SOFTWARE
        val obj = target(hub)
        hub.brain.goto(obj)
        waitUntil(200_000) { hub.brain.tracking }
        val err = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(obj))
        assertTrue(err < 0.06, "GoTo error $err° with a reversed azimuth motor")
    }

    @Test
    fun stopDuringSoftwareGotoLeavesTheMotorsStopped() = runTest {
        val sim = simulator(ignoresB = true)
        val hub = ready(sim)
        hub.onConnect("phone") {}
        // Far away, so the GoTo is still slewing when STOP arrives.
        val here = assertNotNull(hub.brain.pointing().sky)
        val far = catalog.objects.first {
            it.messier && hub.brain.skyOf(it).altDeg in 20.0..70.0 && PointingParams.separation(hub.brain.skyOf(it), here) > 60
        }
        hub.brain.goto(far)
        advanceTimeBy(8_000); runCurrent()
        assertTrue(hub.brain.gotoActive)
        hub.onMessage("phone", """{"type":"stopAll"}""")
        advanceTimeBy(3_000); runCurrent()
        assertFalse(hub.brain.gotoActive)
        assertEquals(0.0, sim.azm.rateDegPerSec)
        assertEquals(0.0, sim.alt.rateDegPerSec)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0.0, sim.azm.rateDegPerSec, "nothing restarts after STOP")
        assertEquals(0.0, sim.alt.rateDegPerSec, "nothing restarts after STOP")
    }

    @Test
    fun aGotoRightAfterStopStillArrives() = runTest {
        val sim = simulator(ignoresB = true)
        val hub = ready(sim, latencyMs = 40)
        hub.onConnect("phone") {}
        val here = assertNotNull(hub.brain.pointing().sky)
        val (first, second) = catalog.objects.filter {
            it.messier && hub.brain.skyOf(it).altDeg in 25.0..70.0 && PointingParams.separation(hub.brain.skyOf(it), here) > 40
        }.let { it[0] to it.last() }
        hub.brain.goto(first)
        advanceTimeBy(8_000); runCurrent()
        // STOP and a new GoTo in the same instant (e.g. from two phones): the old job's last
        // stops, still pending, must not land on the new GoTo and kill it.
        hub.brain.emergencyHalt()
        hub.brain.goto(second)
        waitUntil(200_000) { hub.brain.tracking }
        val err = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(second))
        assertTrue(err < 0.06, "second GoTo error $err°")
    }

    @Test
    fun closingTheAppHaltsGotoAndTracking() = runTest {
        val sim = simulator(ignoresB = true)
        val hub = ready(sim)
        hub.brain.goto(target(hub))
        advanceTimeBy(6_000); runCurrent()
        assertTrue(hub.brain.gotoActive)
        hub.haltAll()
        advanceTimeBy(2_000); runCurrent()
        assertFalse(hub.brain.gotoActive)
        assertFalse(hub.brain.tracking)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0.0, sim.azm.rateDegPerSec, "nothing restarts after the app closes")
        assertEquals(0.0, sim.alt.rateDegPerSec, "nothing restarts after the app closes")
    }

    @Test
    fun roughGotoAlignsWithTheSensorsFirst() = runTest {
        val sim = SimulatedHandController(
            site, { testScheduler.currentTime + t0 }, backlashDeg = 0.0, aligned = false,
            geometry = PointingParams(azOffset = 63.0, altOffset = -8.0),
        )
        val hub = newHub()
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        // Phone compass 4° off, accelerometer 1° off.
        hub.orientationSensor = OrientationSensor { sim.truePointing().let { AltAz((it.azDeg + 4.0) % 360.0, it.altDeg - 1.0) } }
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }
        val star = hub.brain.alignmentSuggestions().first().obj

        hub.onMessage("phone", """{"type":"goto","id":"${star.id}"}""")
        assertTrue(received.last().contains("alinea"), "without rough=true an unaligned GoTo is refused")

        hub.onMessage("phone", """{"type":"goto","id":"${star.id}","rough":true}""")
        assertTrue(assertNotNull(hub.brain.solution).sensorOnly)
        waitUntil(200_000) { hub.brain.tracking }
        val err = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(star))
        assertTrue(err in 0.3..8.0, "rough GoTo lands near the star: $err°")
    }
}
