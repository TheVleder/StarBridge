package org.starbridge.core

import kotlinx.coroutines.test.runTest
import org.starbridge.core.backlash.BacklashCalibrator
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.BacklashValues
import org.starbridge.core.mount.Direction
import org.starbridge.core.mount.FirmwareVersion
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.mount.RaDec
import org.starbridge.core.mount.UnsupportedCommandException
import org.starbridge.core.protocol.NexStarCodec
import org.starbridge.core.sim.SimulatedHandController
import org.starbridge.core.sim.SimulatedMotionSensor
import org.starbridge.core.astro.Astro
import org.starbridge.core.transport.PassThroughException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverSimulatorTest {
    private var now = 1_760_000_000_000L
    private val clock = { now }

    @Test
    fun connectReadsVersionAndModel() = runTest {
        val sim = SimulatedHandController(clock = clock)
        val driver = NexStarDriver(sim, backgroundScope)
        val info = driver.connect()
        assertEquals(FirmwareVersion(4, 21), info.handControlVersion)
        assertEquals(7, info.model)
        assertTrue(driver.isAligned())
        assertEquals(FirmwareVersion(7, 11), driver.getMotorVersion(Axis.AZM))
        val site = driver.getSite()!!
        assertTrue(abs(site.latitudeDeg - 40.4) < 0.01 && abs(site.longitudeDeg + 3.7) < 0.01)
    }

    @Test
    fun oldHandControlRejectsUnsupportedCommands() = runTest {
        val sim = SimulatedHandController(clock = clock, version = 1 to 2)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        assertFailsWith<UnsupportedCommandException> { driver.getRaDec() }
    }

    @Test
    fun slewMovesMotorAndStopHolds() = runTest {
        val sim = SimulatedHandController(clock = clock)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        driver.slew(Axis.AZM, Direction.POSITIVE, 9) // 4°/s
        now += 1000
        driver.stop(Axis.AZM)
        val az = driver.getAltAz().azDeg
        assertTrue(abs(az - 4.0) < 0.01, "az=$az")
        now += 5000
        assertTrue(abs(driver.getAltAz().azDeg - az) < 1e-6)
    }

    @Test
    fun backlashMakesTubeLagOnReversal() = runTest {
        val sim = SimulatedHandController(clock = clock, backlashDeg = 1.0)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        driver.slew(Axis.AZM, Direction.POSITIVE, 9); now += 1000; driver.stop(Axis.AZM)
        val tubeBefore = sim.azm.tubeDeg
        // Reverse for 0.2 s = 0.8° of motor: still inside the 1° of slack → tube must not move.
        driver.slew(Axis.AZM, Direction.NEGATIVE, 9); now += 200; driver.stop(Axis.AZM)
        assertEquals(tubeBefore, sim.azm.tubeDeg, 1e-9)
        // Motor encoder did move: this is exactly what the mount cannot see.
        assertTrue(abs(sim.azm.motorDeg - (tubeBefore + 0.5 - 0.8)) < 1e-6)
    }

    @Test
    fun nativeBacklashReadWrite() = runTest {
        val sim = SimulatedHandController(clock = clock)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        assertEquals(BacklashValues(0, 0), driver.getBacklash(Axis.ALT))
        driver.setBacklash(Axis.ALT, BacklashValues(42, 17))
        assertEquals(BacklashValues(42, 17), driver.getBacklash(Axis.ALT))
    }

    @Test
    fun passThroughErrorIsDetected() = runTest {
        val sim = SimulatedHandController(clock = clock)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        val queueTest = org.starbridge.core.transport.CommandQueue(sim, backgroundScope)
        assertFailsWith<PassThroughException> {
            queueTest.execute(NexStarCodec.passThrough(176, 0x37, ByteArray(0), 1)) // GPS: not present
        }
    }

    @Test
    fun gotoReachesTarget() = runTest {
        val sim = SimulatedHandController(clock = clock, backlashDeg = 0.0)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        val site = driver.getSite()!!
        // Pick a target 40° up in the south.
        val target = Astro.altAzToRaDec(org.starbridge.core.mount.AltAz(180.0, 40.0), site, now)
        driver.gotoRaDec(target)
        assertTrue(driver.isGotoInProgress())
        repeat(600) { now += 100; driver.isGotoInProgress() } // 180° at 4°/s ≈ 45 s
        assertFalse(driver.isGotoInProgress())
        val p = sim.truePointing()
        // The hand control points at the apparent (refracted) position: +1.2' at 40°.
        assertTrue(abs(p.azDeg - 180.0) < 0.05 && abs(p.altDeg - Astro.refract(40.0)) < 0.05, "pointing=$p")
    }

    @Test
    fun gotoRequiresAlignment() = runTest {
        val sim = SimulatedHandController(clock = clock, aligned = false)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        assertFailsWith<IllegalStateException> { driver.gotoRaDec(RaDec(5.0, 20.0)) }
    }

    @Test
    fun calibratorMeasuresSimulatedBacklash() = runTest {
        // Real time inside the simulator: the calibrator uses delay(), which runTest skips,
        // so drive the simulator clock from the test scheduler.
        val sim = SimulatedHandController(clock = { testScheduler.currentTime }, backlashDeg = 0.8)
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        val sensor = SimulatedMotionSensor(sim, clock = { testScheduler.currentTime })
        val result = BacklashCalibrator(driver, sensor, rate = 6, pollMs = 10).calibrate(Axis.AZM)
        assertTrue(abs(result.slackPositiveDeg - 0.8) < 0.05, "pos=${result.slackPositiveDeg}")
        assertTrue(abs(result.slackNegativeDeg - 0.8) < 0.05, "neg=${result.slackNegativeDeg}")
    }
}
