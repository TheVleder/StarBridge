package org.starbridge.core.sim

import org.starbridge.core.mount.AltAz
import org.starbridge.core.pointing.PointingParams
import kotlin.math.cos
import kotlin.math.sin

/**
 * A phone lying on the tube (screen up, its top towards the front of the tube), placed a little
 * crooked on purpose: what its accelerometer reads (gravity in the phone's frame, in g) for the
 * true geometry of the mount and where the tube really is.
 */
class SimulatedPhoneOnTube(
    private val geometry: PointingParams,
    private val pitchDeg: Double = 3.0,
    private val rollDeg: Double = -4.0,
    private val yawDeg: Double = 0.0,
) {
    /** Gravity (x, y, z) for the tube at raw mount angles [tube]. */
    fun gravity(tube: AltAz): DoubleArray {
        val f = PointingParams.direction(geometry.mountToSky(tube).azDeg, geometry.mountToSky(tube).altDeg)
        // The altitude axis: horizontal in the mount frame, 90° to the right of the tube.
        val rSky = geometry.mountToSky(AltAz(tube.azDeg + 90.0 * geometry.azSign, -geometry.altOffset))
        val r = PointingParams.direction(rSky.azDeg, rSky.altDeg)
        val z = cross(r, f)
        // The phone's own placement: yaw on the tube, then front up, then right side up.
        val y0 = rot(f, r, z, yawDeg).second
        val x0 = rot(f, r, z, yawDeg).first
        val p = Math.toRadians(pitchDeg)
        val y1 = add(scale(y0, cos(p)), scale(z, sin(p)))
        val z1 = add(scale(z, cos(p)), scale(y0, -sin(p)))
        val q = Math.toRadians(rollDeg)
        val x2 = add(scale(x0, cos(q)), scale(z1, sin(q)))
        val z2 = add(scale(z1, cos(q)), scale(x0, -sin(q)))
        // Gravity is (0, 0, −1) in the sky frame: its component along each phone axis.
        return doubleArrayOf(-x2[2], -y1[2], -z2[2])
    }

    private fun rot(f: DoubleArray, r: DoubleArray, z: DoubleArray, yaw: Double): Pair<DoubleArray, DoubleArray> {
        val t = Math.toRadians(yaw)
        val x = add(scale(r, cos(t)), scale(f, sin(t)))
        val y = add(scale(f, cos(t)), scale(r, -sin(t)))
        return x to y
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
}
