package org.starbridge.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.starbridge.core.astro.Site
import org.starbridge.core.backlash.GearPlay
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import org.starbridge.core.transport.SerialTransport
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the first nights with the real SLT showed: big gear slack, a hand control with its own
 * GoTo approach direction, a slow serial cable. Arrows, alignment, GoTo and tracking must cope.
 */
class SlackHandlingTest {
    private val catalog = Catalog.loadDefault()
    private val site = Site(40.4168, -3.7038)
    private val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC
    private val tilted = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7)

    /** The real cable: every command takes a while (9600 baud + the hand control thinking). */
    private class Slow(private val inner: SerialTransport, private val ms: Long) : SerialTransport {
        override suspend fun write(data: ByteArray) { delay(ms); inner.write(data) }
        override suspend fun read(timeoutMs: Long) = inner.read(timeoutMs)
        override suspend fun clearInput() = inner.clearInput()
        override fun close() = inner.close()
    }

    private suspend fun TestScope.connect(sim: SimulatedHandController, latencyMs: Long = 0): SessionHub {
        val hub = SessionHub(backgroundScope, catalog, { testScheduler.currentTime + t0 }, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(if (latencyMs > 0) Slow(sim, latencyMs) else sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        hub.onConnect("phone") {}
        return hub
    }

    private fun TestScope.sim(slack: Double, approach: Int = 0, geometry: PointingParams = tilted) = SimulatedHandController(
        site, { testScheduler.currentTime + t0 }, backlashDeg = slack, aligned = false, geometry = geometry, gotoApproach = approach,
    )

    private fun TestScope.waitUntil(maxMs: Long, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < maxMs) {
            advanceTimeBy(500); runCurrent(); waited += 500
        }
        assertTrue(cond(), "condition not met after $maxMs ms")
    }

    /** Holds an arrow like the web page (heartbeat every 200 ms) for [ms], then releases it. */
    private suspend fun TestScope.hold(hub: SessionHub, axis: String, dir: String, rate: Int, ms: Long) {
        hub.onMessage("phone", """{"type":"slew","axis":"$axis","dir":"$dir","rate":$rate}""")
        var t = 0L
        while (t < ms) {
            advanceTimeBy(200); runCurrent()
            hub.onMessage("phone", """{"type":"hold","axis":"$axis"}""")
            t += 200
        }
        hub.onMessage("phone", """{"type":"stop","axis":"$axis"}""")
        advanceTimeBy(500); runCurrent()
    }

    /**
     * A person at the eyepiece: holds the arrow that brings the star closer until it is centred
     * (or just passed), with finer speeds near the end, and taps back after overshooting.
     */
    private suspend fun TestScope.centerByHand(hub: SessionHub, sim: SimulatedHandController, sky: () -> AltAz) {
        repeat(60) {
            sim.truePointing()
            val want = sim.geometry.skyToMount(sky())
            val errAz = PointingParams.angleDiff(want.azDeg, sim.azm.tubeDeg)
            val errAlt = want.altDeg - sim.alt.tubeDeg
            if (abs(errAz) < 0.01 && abs(errAlt) < 0.01) return
            val axis = if (abs(errAz) >= abs(errAlt)) "azm" else "alt"
            val err0 = if (axis == "azm") errAz else errAlt
            val rate = when { abs(err0) > 2 -> 8; abs(err0) > 0.5 -> 6; abs(err0) > 0.08 -> 4; else -> 2 }
            hub.onMessage("phone", """{"type":"slew","axis":"$axis","dir":"${if (err0 > 0) "pos" else "neg"}","rate":$rate}""")
            var waited = 0L
            while (waited < 120_000) {
                advanceTimeBy(100); runCurrent(); waited += 100
                hub.onMessage("phone", """{"type":"hold","axis":"$axis"}""")
                sim.truePointing()
                val w = sim.geometry.skyToMount(sky())
                val e = if (axis == "azm") PointingParams.angleDiff(w.azDeg, sim.azm.tubeDeg) else w.altDeg - sim.alt.tubeDeg
                if (abs(e) < 0.01 || e * err0 < 0) break
            }
            hub.onMessage("phone", """{"type":"stop","axis":"$axis"}""")
            advanceTimeBy(400); runCurrent()
        }
    }

    // --- The gear play model ------------------------------------------------------------

    @Test
    fun gearPlayFollowsShortMovesAndReversals() {
        val p = GearPlay(wraps = false).apply { slack = 1.0 }
        p.observe(10.0)
        assertFalse(p.known, "unknown until the play has been crossed once")
        p.observe(11.2) // long move up: loaded at +0.5
        assertTrue(p.known)
        assertEquals(0.5, p.offset, 1e-9)
        assertEquals(10.7, p.tube(11.2), 1e-9)
        p.observe(10.9) // short tap back: the tube has not moved yet
        assertEquals(0.2, p.offset, 1e-9)
        assertEquals(10.7, p.tube(10.9), 1e-9)
        assertEquals(0.7, p.needed(-1), 1e-9, "0.3° already crossed, 0.7° to go")
        assertEquals(0.3, p.needed(1), 1e-9, "going on up must first undo the tap")
        p.observe(9.9) // the rest of the way back: loaded at −0.5, the tube moved 0.3°
        assertEquals(-0.5, p.offset, 1e-9)
        assertEquals(10.4, p.tube(9.9), 1e-9)
    }

    @Test
    fun gearPlayWrapsInAzimuth() {
        val p = GearPlay(wraps = true).apply { slack = 0.6 }
        p.observe(359.5)
        p.observe(1.0) // +1.5° across north
        assertEquals(0.3, p.offset, 1e-9)
    }

    // --- Arrows: the slack is crossed at once on a reversal ------------------------------------

    private suspend fun TestScope.loadedPositive(slack: Double): Pair<SimulatedHandController, SessionHub> {
        val sim = sim(slack)
        val hub = connect(sim)
        hub.brain.setSlack(Axis.AZM, slack)
        hold(hub, "azm", "pos", rate = 7, ms = 3_000) // loads the gears (+) and learns the motor
        return sim to hub
    }

    @Test
    fun aReversalAtFineSpeedMovesTheTubeAtOnce() = runTest {
        val (sim, hub) = loadedPositive(slack = 1.0)
        sim.truePointing()
        val tube0 = sim.azm.tubeDeg
        // Rate 2 is ~0.017°/s: without the take-up, 1° of slack would take a minute of nothing.
        hold(hub, "azm", "neg", rate = 2, ms = 4_000)
        sim.truePointing()
        assertTrue(sim.azm.tubeDeg < tube0 - 0.02, "the tube must already move back: ${sim.azm.tubeDeg - tube0}°")
        assertEquals(0.0, sim.azm.rateDegPerSec)
    }

    @Test
    fun aShortTapAfterAReversalStillCrossesTheWholeSlack() = runTest {
        val (sim, hub) = loadedPositive(slack = 1.0)
        sim.truePointing()
        val motor0 = sim.azm.motorDeg
        val tube0 = sim.azm.tubeDeg
        // A quick tap: press and release 100 ms later (the release comes while crossing).
        val press = launch { hub.onMessage("phone", """{"type":"slew","axis":"azm","dir":"neg","rate":2}""") }
        advanceTimeBy(100); runCurrent()
        launch { hub.onMessage("phone", """{"type":"stop","axis":"azm"}""") }
        advanceTimeBy(4_000); runCurrent()
        press.join()
        sim.truePointing()
        assertTrue(abs(sim.azm.motorDeg - motor0 + 1.0) < 0.05, "motor crossed the slack: ${sim.azm.motorDeg - motor0}°")
        assertTrue(abs(sim.azm.tubeDeg - tube0) < 0.05, "and the tube barely moved: ${sim.azm.tubeDeg - tube0}°")
        assertEquals(0.0, sim.azm.rateDegPerSec, "stopped after the take-up")
    }

    @Test
    fun stopCutsTheTakeUpShort() = runTest {
        val (sim, hub) = loadedPositive(slack = 2.0)
        sim.truePointing()
        val motor0 = sim.azm.motorDeg
        val press = launch { hub.onMessage("phone", """{"type":"slew","axis":"azm","dir":"neg","rate":2}""") }
        advanceTimeBy(200); runCurrent()
        hub.onMessage("phone", """{"type":"stopAll"}""")
        advanceTimeBy(300); runCurrent()
        assertEquals(0.0, sim.azm.rateDegPerSec)
        advanceTimeBy(5_000); runCurrent()
        press.join()
        assertEquals(0.0, sim.azm.rateDegPerSec, "nothing restarts after STOP")
        assertTrue(abs(sim.azm.motorDeg - motor0) < 1.0, "it did not finish the 2° take-up")
    }

    @Test
    fun takeUpCanBeSwitchedOff() = runTest {
        val (sim, hub) = loadedPositive(slack = 1.0)
        hub.onMessage("phone", """{"type":"setSetting","key":"manualTakeUp","value":"false"}""")
        sim.truePointing()
        val tube0 = sim.azm.tubeDeg
        hold(hub, "azm", "neg", rate = 2, ms = 4_000)
        sim.truePointing()
        assertTrue(abs(sim.azm.tubeDeg - tube0) < 1e-6, "without take-up the tube waits in the slack")
    }

    // --- Alignment and GoTo with big slack and a hand control that approaches its own way ----

    @Test
    fun gotoBackToTheStarJustCentredLandsOnIt() = runTest {
        // 1° of slack, and the hand control finishes its GoTos moving down/left.
        val sim = sim(slack = 1.0, approach = -1)
        val hub = connect(sim)
        hub.brain.setSlack(Axis.AZM, 1.0)
        hub.brain.setSlack(Axis.ALT, 1.0)
        // First move: learn both motors.
        hold(hub, "azm", "pos", rate = 7, ms = 2_000)
        hold(hub, "alt", "pos", rate = 7, ms = 2_000)
        val stars = hub.brain.alignmentSuggestions().map { it.obj }
        val first = stars.first()
        centerByHand(hub, sim) { hub.brain.skyOf(first) }
        hub.onMessage("phone", """{"type":"alignAdd","id":"${first.id}"}""")
        val second = hub.brain.alignmentSuggestions().first().obj
        centerByHand(hub, sim) { hub.brain.skyOf(second) }
        hub.onMessage("phone", """{"type":"alignAdd","id":"${second.id}"}""")

        // Back to the first star, then to the one just centred.
        for (star in listOf(first, second)) {
            hub.onMessage("phone", """{"type":"goto","id":"${star.id}"}""")
            waitUntil(240_000) { hub.brain.tracking && hub.brain.trackingLabel?.contains(star.id) == true }
            advanceTimeBy(5_000); runCurrent()
            val err = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(star))
            assertTrue(err < 0.08, "GoTo to ${star.id} off by $err° (slack 1°)")
        }
        // And a new object.
        val target = catalog.objects.first { it.messier && hub.brain.skyOf(it).altDeg in 30.0..70.0 }
        hub.onMessage("phone", """{"type":"goto","id":"${target.id}"}""")
        waitUntil(240_000) { hub.brain.tracking && hub.brain.trackingLabel?.contains(target.id) == true }
        advanceTimeBy(5_000); runCurrent()
        val err = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(target))
        assertTrue(err < 0.1, "GoTo to ${target.id} off by $err°")
    }

    // --- Tracking: smooth over a slow cable ---------------------------------------------------

    @Test
    fun trackingIsSmoothOverASlowCable() = runTest {
        // Real-world: each command takes ~120 ms and the DC motors wander ±20 % around the speed.
        val sim = SimulatedHandController(
            site, { testScheduler.currentTime + t0 }, backlashDeg = 0.6, aligned = false, geometry = tilted, speedNoise = 0.2,
        )
        val hub = connect(sim, latencyMs = 120)
        hub.brain.setSlack(Axis.AZM, 0.6)
        hub.brain.setSlack(Axis.ALT, 0.6)
        repeat(2) {
            val s = hub.brain.alignmentSuggestions().first()
            sim.centerOn(hub.brain.skyOf(s.obj))
            hub.brain.addAlignmentStar(s.obj)
        }
        val target = catalog.objects.first { it.messier && hub.brain.skyOf(it).altDeg in 30.0..70.0 }
        hub.brain.goto(target)
        waitUntil(240_000) { hub.brain.tracking }
        advanceTimeBy(30_000); runCurrent() // settle

        val az = mutableListOf<Double>()
        val alt = mutableListOf<Double>()
        repeat(300) {
            advanceTimeBy(1_000); runCurrent()
            az += sim.azm.rateDegPerSec
            alt += sim.alt.rateDegPerSec
            val e = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(target))
            assertTrue(e < 0.03, "tracking error $e° after ${it + 1} s")
        }
        for ((name, rates) in listOf("az" to az, "alt" to alt)) {
            val mean = rates.average() * 3600 // ″/s
            val meanChange = rates.zipWithNext { a, b -> abs(b - a) }.average() * 3600
            // Smooth: second to second the speed changes by a few % (the old 1-second "reach it
            // now" loop chased every wobble of the motor: ~10 % jumps each second, 2.2″/s here).
            assertTrue(
                meanChange < 0.05 * abs(mean) + 0.3,
                "$name speed changes ${"%.2f".format(meanChange)}″/s per second around ${"%.2f".format(mean)}″/s",
            )
        }
    }
}
