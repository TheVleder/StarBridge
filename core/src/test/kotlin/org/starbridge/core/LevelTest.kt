package org.starbridge.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.AlignmentSolver
import org.starbridge.core.server.MemorySettings
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedHandController
import org.starbridge.core.pointing.LevelFit
import org.starbridge.core.pointing.LevelSample
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.sim.SimulatedPhoneOnTube
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tilt of the mount measured with a phone on the tube while it turns in azimuth. */
class LevelTest {
    private val geometry = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7)

    /** Where the axis really leans: (tilt, sky azimuth), from the model's own rotation. */
    private fun truth(p: PointingParams): Pair<Double, Double> {
        val tE = Math.toRadians(p.tiltEast)
        val tN = Math.toRadians(p.tiltNorth)
        val axis = doubleArrayOf(kotlin.math.sin(tN), -kotlin.math.sin(tE) * cos(tN), cos(tE) * cos(tN))
        return Math.toDegrees(acos(axis[2])) to ((Math.toDegrees(atan2(axis[0], axis[1])) + 360) % 360)
    }

    private fun samples(phone: SimulatedPhoneOnTube, start: Double, alt: Double) =
        listOf(-135.0, -45.0, 45.0, 135.0).map { d ->
            val a = start + d
            val g = phone.gravity(AltAz(a, alt))
            LevelSample(a, g[0], g[1], g[2])
        }

    @Test
    fun theTiltComesOutWhateverHowThePhoneLies() {
        val (tilt, toward) = truth(geometry)
        for (alt in listOf(0.0, 20.0, 45.0)) {
            val r = assertNotNull(LevelFit.fit(samples(SimulatedPhoneOnTube(geometry, pitchDeg = 3.0, rollDeg = -4.0), 100.0, alt), 1, 0))
            assertEquals(tilt, r.tiltDeg, 0.01, "tilt with the tube at $alt°")
            assertEquals(0.0, PointingParams.angleDiff(r.towardAz(geometry.azOffset), toward), 0.5, "direction with the tube at $alt°")
            assertTrue(r.rmsDeg < 0.01, "rms ${r.rmsDeg}")
            // The model's tilts come back.
            val (tE, tN) = r.tilts(geometry.azOffset)
            assertEquals(geometry.tiltEast, tE, 0.02)
            assertEquals(geometry.tiltNorth, tN, 0.02)
        }
    }

    @Test
    fun aPhoneTurnedOnTheTubeTurnsTheDirectionByAsMuch() {
        val (tilt, toward) = truth(geometry)
        val r = assertNotNull(LevelFit.fit(samples(SimulatedPhoneOnTube(geometry, yawDeg = 5.0), 30.0, 0.0), 1, 0))
        assertEquals(tilt, r.tiltDeg, 0.02)
        assertTrue(abs(PointingParams.angleDiff(r.towardAz(geometry.azOffset), toward)) < 6.0)
    }

    @Test
    fun levelWithTheIphoneThenOneStarAlignsTheWholeSky() = runTest {
        val site = Site(40.4168, -3.7038)
        val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC
        val clock = { testScheduler.currentTime + t0 }
        val sim = SimulatedHandController(site, clock, backlashDeg = 0.0, aligned = false, geometry = geometry)
        val catalog = Catalog.loadDefault()
        val settings = MemorySettings()
        val hub = SessionHub(backgroundScope, catalog, clock, monotonic = { testScheduler.currentTime }, settings = settings)
        hub.brain.updateGps(site, 5.0)
        val driver = NexStarDriver(sim, backgroundScope)
        hub.attachMount(driver, driver.connect())
        val pc = mutableListOf<String>()
        hub.onConnect("pc") { pc += it }
        // The iPhone app, lying a bit crooked on the tube: streams its gravity while asked to.
        val phone = SimulatedPhoneOnTube(geometry, pitchDeg = 2.0, rollDeg = -3.0)
        var stream: Job? = null
        hub.onConnect("iphone") { text ->
            if (text.contains("\"sensorCmd\"")) {
                stream?.cancel()
                if (text.contains("\"on\":true")) stream = backgroundScope.launch {
                    while (isActive) {
                        sim.truePointing()
                        val g = phone.gravity(AltAz(sim.azm.tubeDeg, sim.alt.tubeDeg))
                        hub.onMessage("iphone", """{"type":"gravity","source":"iphone","x":${g[0]},"y":${g[1]},"z":${g[2]}}""")
                        delay(100)
                    }
                }
            }
        }
        hub.onMessage("iphone", """{"type":"camStatus","source":"iphone","activity":"idle"}""")

        hub.onMessage("pc", """{"type":"levelStart"}""")
        var waited = 0
        while (pc.none { it.contains("\"phase\":\"done\"") || it.contains("\"phase\":\"failed\"") } && waited < 900) {
            advanceTimeBy(1_000); runCurrent(); waited++
        }
        assertTrue(pc.any { it.contains("\"phase\":\"done\"") }, pc.lastOrNull { it.contains("\"level\"") } ?: "no level message")
        val level = assertNotNull(hub.brain.level)
        val (tilt, toward) = truth(geometry)
        assertEquals(tilt, level.tiltDeg, 0.03)
        assertTrue(settings.get(SessionHub.KEY_LEVEL)!!.contains("tiltDeg"), "kept for the next session")
        assertTrue(stream?.isActive != true, "the iPhone was told to stop")

        // One star now gives the whole model: compare far from it with what one star alone gives.
        val star = hub.brain.alignmentSuggestions().first().obj
        sim.centerOn(hub.brain.skyOf(star))
        hub.brain.addAlignmentStar(star)
        val sol = assertNotNull(hub.brain.solution)
        assertEquals(0.0, PointingParams.angleDiff(level.towardAz(sol.params.azOffset, sol.params.azSign), toward), 1.0)
        val far = catalog.objects.first { o ->
            val s = hub.brain.skyOf(o)
            s.altDeg in 25.0..70.0 && PointingParams.separation(s, hub.brain.skyOf(star)) > 80
        }
        val sky = hub.brain.skyOf(far)
        val withLevel = PointingParams.separation(sol.params.mountToSky(geometry.skyToMount(sky)), sky)
        val alone = assertNotNull(AlignmentSolver.solve(hub.brain.points)).params
        val withoutLevel = PointingParams.separation(alone.mountToSky(geometry.skyToMount(sky)), sky)
        assertTrue(withLevel < 0.08, "with the level: ${"%.3f".format(withLevel)}°")
        assertTrue(withoutLevel > 0.5, "one star without it: ${"%.3f".format(withoutLevel)}°")
    }

    @Test
    fun aLevelBaseAndTooFewPositions() {
        val level = PointingParams(azOffset = 20.0)
        val r = assertNotNull(LevelFit.fit(samples(SimulatedPhoneOnTube(level), 0.0, 10.0), 1, 0))
        assertTrue(r.tiltDeg < 0.01)
        val one = samples(SimulatedPhoneOnTube(geometry), 0.0, 0.0).take(1)
        assertNull(LevelFit.fit(one, 1, 0))
        val close = listOf(0.0, 20.0).map { val g = SimulatedPhoneOnTube(geometry).gravity(AltAz(it, 0.0)); LevelSample(it, g[0], g[1], g[2]) }
        assertNull(LevelFit.fit(close, 1, 0), "20° apart cannot tell the tilt from the phone's placement")
    }
}
