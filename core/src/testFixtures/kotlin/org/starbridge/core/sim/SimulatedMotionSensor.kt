package org.starbridge.core.sim

import org.starbridge.core.backlash.MotionSensor
import org.starbridge.core.mount.Axis
import kotlin.math.abs

/** Plays the role of the phone gyroscope: reports the real tube speed of the simulator. */
class SimulatedMotionSensor(
    private val sim: SimulatedHandController,
    private val clock: () -> Long = System::currentTimeMillis,
) : MotionSensor {
    private val last = mutableMapOf<Axis, Pair<Long, Double>>()

    override val noiseFloor = 0.002

    override fun angularSpeed(axis: Axis): Double? {
        sim.truePointing() // advances the simulation
        val pos = sim.axis(axis).tubeDeg
        val t = clock()
        val prev = last.put(axis, t to pos) ?: return null
        val dt = (t - prev.first) / 1000.0
        if (dt <= 0) return null
        return abs(pos - prev.second) / dt
    }
}
