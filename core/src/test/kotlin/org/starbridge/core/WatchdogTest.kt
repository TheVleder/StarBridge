package org.starbridge.core

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.starbridge.core.control.MotionController
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.Direction
import org.starbridge.core.mount.MountDriver
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.sim.SimulatedHandController
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchdogTest {

    @Test
    fun stopsWhenHeartbeatsStop() = runTest {
        val sim = SimulatedHandController(clock = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        val mc = MotionController(driver, backgroundScope, deadlineMs = 750, now = { testScheduler.currentTime })

        mc.startSlew("phone", Axis.AZM, Direction.POSITIVE, 9)
        repeat(8) { advanceTimeBy(250); mc.hold("phone", Axis.AZM) } // 2 s of heartbeats
        assertTrue(mc.isMoving(Axis.AZM))
        assertTrue(sim.azm.rateDegPerSec > 0)

        // Phone locked: no more heartbeats.
        advanceTimeBy(1100)
        assertFalse(mc.isMoving(Axis.AZM))
        assertTrue(sim.azm.rateDegPerSec == 0.0, "mount must be stopped")
    }

    @Test
    fun clientDisconnectStopsItsAxes() = runTest {
        val sim = SimulatedHandController(clock = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        val mc = MotionController(driver, backgroundScope, now = { testScheduler.currentTime })
        mc.startSlew("phone", Axis.ALT, Direction.NEGATIVE, 5)
        mc.clientGone("phone")
        assertTrue(sim.alt.rateDegPerSec == 0.0)
    }

    @Test
    fun onlyTheControllerCanMove() = runTest {
        val sim = SimulatedHandController(clock = { testScheduler.currentTime })
        val driver = NexStarDriver(sim, backgroundScope)
        driver.connect()
        val mc = MotionController(driver, backgroundScope, now = { testScheduler.currentTime })
        mc.startSlew("phone", Axis.AZM, Direction.POSITIVE, 5)
        assertFailsWith<IllegalStateException> { mc.startSlew("tablet", Axis.AZM, Direction.NEGATIVE, 5) }
        mc.stopAll("tablet pressed STOP") // anyone can stop
        assertTrue(sim.azm.rateDegPerSec == 0.0)
    }

    private class FlakyStop(private val d: MountDriver, var failures: Int) : MountDriver by d {
        override suspend fun stop(axis: Axis) {
            if (failures-- > 0) throw java.io.IOException("simulated timeout")
            d.stop(axis)
        }
    }

    @Test
    fun failedStopIsRetriedUntilItWorks() = runTest {
        val sim = SimulatedHandController(clock = { testScheduler.currentTime })
        val real = NexStarDriver(sim, backgroundScope)
        real.connect()
        val flaky = FlakyStop(real, failures = 2)
        val mc = MotionController(flaky, backgroundScope, deadlineMs = 750, now = { testScheduler.currentTime })
        mc.startSlew("phone", Axis.AZM, Direction.POSITIVE, 9)
        mc.stopAxis(Axis.AZM) // first attempt fails
        assertTrue(sim.azm.rateDegPerSec > 0)
        advanceTimeBy(2000) // watchdog retries
        assertTrue(sim.azm.rateDegPerSec == 0.0, "stop must eventually succeed")
    }
}
