package org.starbridge.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.catalog.CatalogObject
import org.starbridge.core.control.OrientationSensor
import org.starbridge.core.control.PointingMode
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.AlignmentPoint
import org.starbridge.core.pointing.AlignmentSolver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrainTest {
    private val catalog = Catalog.loadDefault()
    private val site = Site(40.4168, -3.7038)
    private val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC

    // Hidden geometry of the simulated mount: powered on pointing anywhere, base not level.
    private val tilted = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7)

    // --- Pure model ------------------------------------------------------------------

    @Test
    fun modelRoundTrip() {
        val rnd = Random(1)
        repeat(200) {
            val p = PointingParams(rnd.nextDouble(-180.0, 180.0), rnd.nextDouble(-20.0, 20.0),
                rnd.nextDouble(-3.0, 3.0), rnd.nextDouble(-3.0, 3.0), if (rnd.nextBoolean()) 1 else -1)
            val sky = AltAz(rnd.nextDouble(0.0, 360.0), rnd.nextDouble(-10.0, 85.0))
            val back = p.mountToSky(p.skyToMount(sky))
            assertTrue(PointingParams.separation(back, sky) < 1e-7, "$p $sky -> $back")
        }
    }

    private fun points(hidden: PointingParams, skies: List<AltAz>) = skies.mapIndexed { i, s ->
        AlignmentPoint("S$i", hidden.skyToMount(s), s, 0.05, 0.05)
    }

    @Test
    fun solverRecoversTiltedMountWithTwoStars() {
        val sol = assertNotNull(AlignmentSolver.solve(points(tilted, listOf(AltAz(120.0, 35.0), AltAz(250.0, 55.0)))))
        val rnd = Random(2)
        repeat(100) {
            val sky = AltAz(rnd.nextDouble(0.0, 360.0), rnd.nextDouble(10.0, 80.0))
            val err = PointingParams.separation(sol.params.mountToSky(tilted.skyToMount(sky)), sky)
            assertTrue(err < 0.05, "error $err° at $sky with ${sol.params}")
        }
    }

    @Test
    fun solverHandlesReversedAzimuthEncoder() {
        val reversed = tilted.copy(azSign = -1)
        val skies = listOf(AltAz(40.0, 30.0), AltAz(170.0, 60.0), AltAz(290.0, 40.0))
        val sol = assertNotNull(AlignmentSolver.solve(points(reversed, skies)))
        assertEquals(-1, sol.params.azSign)
        assertTrue(sol.rmsArcmin < 0.5, "rms ${sol.rmsArcmin}")
    }

    @Test
    fun oneStarOnLevelBaseIsEnough() {
        val level = PointingParams(azOffset = 77.0, altOffset = -4.0)
        val sol = assertNotNull(AlignmentSolver.solve(points(level, listOf(AltAz(200.0, 45.0)))))
        val err = PointingParams.separation(sol.params.mountToSky(level.skyToMount(AltAz(30.0, 60.0))), AltAz(30.0, 60.0))
        assertTrue(err < 0.01, "err $err")
    }

    // --- End to end through the hub, the driver and the byte-level simulator -------------

    private fun TestScope.setup(geometry: PointingParams, slack: Double = 0.6): Triple<SimulatedHandController, SessionHub, () -> Long> {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, backlashDeg = slack, aligned = false, geometry = geometry)
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        return Triple(sim, hub, clock)
    }

    private fun TestScope.waitUntil(maxMs: Long, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < maxMs) {
            advanceTimeBy(500); runCurrent(); waited += 500
        }
        assertTrue(cond(), "condition not met after $maxMs ms")
    }

    private fun pickTarget(hub: SessionHub, minAlt: Double = 30.0, maxAlt: Double = 70.0): CatalogObject =
        catalog.objects.first { it.messier && hub.brain.skyOf(it).altDeg in minAlt..maxAlt }

    @Test
    fun twoStarAlignmentThenGotoAndTrack() = runTest {
        val (sim, hub, clock) = setup(tilted)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val brain = hub.brain
        assertEquals(PointingMode.STARBRIDGE, brain.mode)
        assertEquals(0, sim.tracking, "hand control tracking must be off in StarBridge mode")
        brain.updateGps(site, 5.0)
        assertNotNull(sim.timeBytes, "GPS time must be written to the hand control")
        brain.setSlack(Axis.AZM, 0.6)
        brain.setSlack(Axis.ALT, 0.6)

        repeat(2) {
            val s = brain.alignmentSuggestions().first()
            sim.centerOn(brain.skyOf(s.obj))
            brain.addAlignmentStar(s.obj)
        }
        val sol = assertNotNull(brain.solution)
        assertEquals(2, sol.starCount)
        assertTrue(sol.rmsArcmin < 1.0, "rms ${sol.rmsArcmin}")

        val target = pickTarget(hub)
        brain.goto(target)
        waitUntil(200_000) { brain.tracking }
        val err = PointingParams.separation(sim.truePointing(), brain.skyOf(target))
        assertTrue(err < 0.05, "GoTo error $err° on ${target.id}")

        // Ten minutes of tracking: the object must stay centred.
        repeat(20) {
            advanceTimeBy(30_000); runCurrent()
            val e = PointingParams.separation(sim.truePointing(), brain.skyOf(target))
            assertTrue(e < 0.05, "tracking error $e° after ${(it + 1) * 30} s")
        }

        // STOP: tracking must not restart the motors.
        hub.onMessage("phone", """{"type":"stopAll"}""")
        advanceTimeBy(5_000); runCurrent()
        assertFalse(brain.tracking)
        assertEquals(0.0, sim.azm.rateDegPerSec)
        assertEquals(0.0, sim.alt.rateDegPerSec)
        assertTrue(clock() > t0)
    }

    @Test
    fun sensorRoughAlignmentLandsNearTheStarThenOneStarRefines() = runTest {
        val level = PointingParams(azOffset = 63.0, altOffset = -8.0)
        val (sim, hub, _) = setup(level, slack = 0.0)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val brain = hub.brain
        // Phone compass 6° off, accelerometer 1° off.
        hub.orientationSensor = OrientationSensor {
            sim.truePointing().let { AltAz((it.azDeg + 6.0) % 360.0, it.altDeg - 1.0) }
        }
        brain.addSensorAlignment()
        assertTrue(assertNotNull(brain.solution).sensorOnly)

        val star = brain.alignmentSuggestions().first().obj
        brain.goto(star)
        waitUntil(200_000) { brain.tracking }
        val rough = PointingParams.separation(sim.truePointing(), brain.skyOf(star))
        assertTrue(rough in 0.5..8.0, "rough alignment error $rough°")

        sim.centerOn(brain.skyOf(star))
        brain.addAlignmentStar(star)
        val target = pickTarget(hub)
        brain.goto(target)
        waitUntil(200_000) { brain.tracking && brain.trackingLabel?.contains(target.id) == true }
        val err = PointingParams.separation(sim.truePointing(), brain.skyOf(target))
        assertTrue(err < 0.1, "1-star GoTo error $err°")
    }

    @Test
    fun manualSlewPausesTrackingAndResumesOnNewSpot() = runTest {
        val (sim, hub, _) = setup(tilted, slack = 0.0)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val brain = hub.brain
        repeat(2) {
            val s = brain.alignmentSuggestions().first()
            sim.centerOn(brain.skyOf(s.obj)); brain.addAlignmentStar(s.obj)
        }
        brain.startTracking(label = "aquí")
        advanceTimeBy(5_000); runCurrent()

        hub.onMessage("phone", """{"type":"slew","axis":"azm","dir":"pos","rate":6}""")
        repeat(8) { advanceTimeBy(250); hub.onMessage("phone", """{"type":"hold","axis":"azm"}""") }
        hub.onMessage("phone", """{"type":"stop","axis":"azm"}""")
        advanceTimeBy(3_000); runCurrent()
        val spot = assertNotNull(brain.pointing().raDec)

        advanceTimeBy(300_000); runCurrent() // 5 min
        val now = assertNotNull(brain.pointing().raDec)
        val drift = abs(now.raHours - spot.raHours) * 15 * 60 + abs(now.decDeg - spot.decDeg) * 60
        assertTrue(brain.tracking, "tracking must resume after a manual slew")
        assertTrue(drift < 3, "drift $drift arcmin")
    }

    @Test
    fun handControlModeWhenAlreadyAligned() = runTest {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, aligned = true)
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        assertEquals(PointingMode.HAND_CONTROL, hub.brain.mode)
        val target = pickTarget(hub)
        hub.brain.goto(target)
        waitUntil(200_000) { sim.truePointing(); sim.azm.gotoTarget == null && sim.alt.gotoTarget == null }
        val err = PointingParams.separation(sim.truePointing(), hub.brain.skyOf(target))
        assertTrue(err < 0.5, "HC GoTo error $err°") // includes 0.3° of uncompensated slack
        hub.brain.syncHandControl(target)
        assertEquals(1, sim.syncCount)
    }

    @Test
    fun equatorialMountUsesTheHandControlAndTracksEquatorially() = runTest {
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, aligned = false, model = 20) // AVX
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        assertEquals(PointingMode.HAND_CONTROL, hub.brain.mode)
        assertTrue(runCatching { hub.brain.setMode(PointingMode.STARBRIDGE) }.isFailure)
        sim.aligned = true // aligned on the hand control after StarBridge connected
        sim.tracking = 0
        hub.brain.goto(pickTarget(hub))
        hub.brain.startTracking()
        assertEquals(2, sim.tracking) // EQ north (the site is in Madrid), never alt-az
    }

    @Test
    fun refusesTargetsBelowHorizonAndUnalignedGoto() = runTest {
        val (sim, hub, _) = setup(tilted)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val below = catalog.objects.first { it.messier && hub.brain.skyOf(it).altDeg < -10 }
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }
        hub.onMessage("phone", """{"type":"goto","id":"${below.id}"}""")
        assertTrue(received.last().contains("bajo el horizonte"))
        hub.onMessage("phone", """{"type":"goto","id":"${pickTarget(hub).id}"}""")
        assertTrue(received.last().contains("alinea"))
    }
}
