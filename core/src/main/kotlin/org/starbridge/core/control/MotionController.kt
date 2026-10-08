package org.starbridge.core.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.Direction
import org.starbridge.core.mount.MountDriver

/**
 * Hold-to-move with a dead-man watchdog (docs/ARQUITECTURA.md):
 * - a client starts a slew and must send [hold] at least every [deadlineMs];
 * - if it stops doing so (phone locked, Safari in background, WiFi lost), the axis stops;
 * - only one client controls the mount at a time; anyone can stop it;
 * - a stop that fails is retried until it succeeds.
 *
 * The lock only protects the bookkeeping; serial I/O always happens outside it, so a slow
 * command can never delay a STOP or the watchdog.
 */
class MotionController(
    private val mount: MountDriver,
    private val scope: CoroutineScope,
    private val deadlineMs: Long = 750,
    /** Must be monotonic (never jumps back when the phone syncs its clock). */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onEvent: (String) -> Unit = {},
    /** Every stop of a hold-to-move slew: released, watchdog, client gone or STOP. */
    private val onAxisStopped: (Axis) -> Unit = {},
) {
    private class ActiveSlew(val clientId: String, val epoch: Long, var lastHold: Long) {
        /** Crossing the gear slack at speed before the real slew (bounded, always finished). */
        var takingUp = false
        /** Released during the take-up: stop as soon as it is done. */
        var releaseRequested = false
    }

    private val mutex = Mutex()
    private val active = mutableMapOf<Axis, ActiveSlew>()
    private val pendingStop = mutableSetOf<Axis>()
    private var epoch = 0L
    private var watchdog: Job? = null

    /** Client currently allowed to move the mount (last one that asked). */
    @Volatile var controllerId: String? = null
        private set

    fun takeControl(clientId: String) {
        controllerId = clientId
    }

    /**
     * Starts a hold-to-move slew. With [takeUp] (a reversal with known gear slack), the motor
     * first crosses the slack fast so the tube answers at once at any speed; the take-up is
     * always completed, even if the button is released meanwhile (only STOP cuts it short).
     */
    suspend fun startSlew(
        clientId: String,
        axis: Axis,
        direction: Direction,
        rate: Int,
        takeUp: TakeUp? = null,
        /** Any speed in arcsec/s instead of the 9 fixed ones (the computer's speed slider). */
        arcsecPerSec: Double? = null,
    ) {
        val myEpoch = mutex.withLock {
            if (controllerId == null) controllerId = clientId
            if (controllerId != clientId) throw IllegalStateException("Otro dispositivo está moviendo el telescopio")
            val e = ++epoch
            active[axis] = ActiveSlew(clientId, e, now()).also { it.takingUp = takeUp != null }
            anyMoving = true
            e
        }
        ensureWatchdog()
        if (takeUp != null) {
            try {
                runTakeUp(axis, direction, myEpoch, takeUp)
            } catch (e: org.starbridge.core.transport.DiscardedByStopException) {
                return // STOP: already stopped
            } catch (e: Exception) {
                stopAxisSafely(axis, null)
                throw e
            }
            val next = mutex.withLock {
                val s = active[axis]
                when {
                    s == null || s.epoch != myEpoch -> AfterTakeUp.ABORTED // STOP, client gone, another slew
                    s.releaseRequested -> AfterTakeUp.RELEASE
                    else -> {
                        s.takingUp = false
                        s.lastHold = now() // the watchdog starts counting from here
                        AfterTakeUp.CONTINUE
                    }
                }
            }
            when (next) {
                AfterTakeUp.ABORTED -> return
                AfterTakeUp.RELEASE -> return stopAxisSafely(axis, null)
                AfterTakeUp.CONTINUE -> Unit
            }
        }
        try {
            if (arcsecPerSec != null) mount.slewVariable(axis, direction, arcsecPerSec) else mount.slew(axis, direction, rate)
        } catch (e: org.starbridge.core.transport.DiscardedByStopException) {
            return // a STOP overtook this slew: nothing was sent
        } catch (e: Exception) {
            // The controller may have executed it anyway: make sure the axis is stopped.
            stopAxisSafely(axis, null)
            throw e
        }
        // A stop may have overtaken the queued slew: re-send it so the slew does not win.
        val overtaken = mutex.withLock { active[axis]?.epoch != myEpoch }
        if (overtaken) stopAxisSafely(axis, null)
    }

    /** Heartbeat while the button is held. */
    suspend fun hold(clientId: String, axis: Axis) = mutex.withLock {
        active[axis]?.takeIf { it.clientId == clientId }?.lastHold = now()
    }

    /** The button was released. During a take-up it only asks it to stop once done. */
    suspend fun stopAxis(axis: Axis) {
        val deferred = mutex.withLock {
            active[axis]?.takeIf { it.takingUp }?.let { it.releaseRequested = true; true } ?: false
        }
        if (!deferred) stopAxisSafely(axis, null)
    }

    private enum class AfterTakeUp { ABORTED, RELEASE, CONTINUE }

    private suspend fun stillMine(axis: Axis, epoch: Long) = mutex.withLock { active[axis]?.epoch == epoch }

    /**
     * Crosses the slack: fast, then slower for the last bit, reading the encoder, until it has
     * travelled what [takeUp] says for the direction it really moves. Leaves the motor running.
     */
    private suspend fun runTakeUp(axis: Axis, direction: Direction, epoch: Long, takeUp: TakeUp) {
        fun angle(p: org.starbridge.core.mount.AltAz) = if (axis == Axis.AZM) p.azDeg else p.altDeg
        val start = angle(mount.getAltAz())
        var needed: Double? = null
        var rateNow = 0
        var lastTravel = 0.0
        var lastProgressAt = now()
        val startedAt = now()
        while (stillMine(axis, epoch)) {
            val a = angle(mount.getAltAz())
            val signed = if (axis == Axis.AZM) org.starbridge.core.pointing.PointingParams.angleDiff(a, start) else a - start
            val travel = kotlin.math.abs(signed)
            // How much is needed depends on the direction the encoder really goes.
            if (needed == null && travel > TAKE_UP_SEEN_DEG) needed = takeUp.needed(if (signed > 0) 1 else -1)
            val remaining = (needed ?: Double.MAX_VALUE) - travel
            if (remaining <= TAKE_UP_TOLERANCE_DEG) return
            val wanted = if (remaining > TAKE_UP_SLOW_DEG) TAKE_UP_FAST_RATE else TAKE_UP_SLOW_RATE
            if (wanted != rateNow) {
                mount.slew(axis, direction, wanted)
                rateNow = wanted
            }
            if (travel - lastTravel > TAKE_UP_SEEN_DEG) {
                lastTravel = travel
                lastProgressAt = now()
            }
            if (now() - lastProgressAt > TAKE_UP_STALL_MS) throw IllegalStateException("el motor no se mueve")
            if (now() - startedAt > TAKE_UP_MAX_MS) throw IllegalStateException("la holgura no termina de recogerse")
            delay(TAKE_UP_POLL_MS)
        }
    }

    /** Emergency STOP from any client. */
    suspend fun stopAll(reason: String) {
        mutex.withLock { active.clear(); anyMoving = false }
        Axis.entries.forEach(onAxisStopped)
        onEvent(reason)
        try {
            mount.emergencyStop()
        } catch (e: Exception) {
            mutex.withLock { pendingStop += Axis.entries }
            ensureWatchdog()
            onEvent("¡El STOP falló (${e.message})! Reintentando…")
            throw e
        }
    }

    /** A client vanished (socket closed): stop whatever it was moving. */
    suspend fun clientGone(clientId: String) {
        val axes = mutex.withLock {
            if (controllerId == clientId) controllerId = null
            active.filterValues { it.clientId == clientId }.keys.toList()
        }
        axes.forEach { stopAxisSafely(it, "Parado: el dispositivo que lo movía se ha desconectado") }
    }

    suspend fun isMoving(axis: Axis) = mutex.withLock { active.containsKey(axis) }

    /** Lock-free snapshot for non-suspending callers (camera side): any manual slew running. */
    @Volatile var anyMoving: Boolean = false
        private set

    fun close() {
        watchdog?.cancel()
    }

    private fun ensureWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive) {
                delay(deadlineMs / 3)
                val (expired, retry) = mutex.withLock {
                    // A take-up runs on its own (bounded in time and travel): no heartbeats needed.
                    active.filterValues { !it.takingUp && now() - it.lastHold > deadlineMs }.keys.toList() to pendingStop.toList()
                }
                expired.forEach { stopAxisSafely(it, "Parado por seguridad: se perdió el contacto con el iPhone") }
                retry.forEach { stopAxisSafely(it, null) }
            }
        }
    }

    /** [reason] is shown to the user; null for normal stops (button released). */
    private suspend fun stopAxisSafely(axis: Axis, reason: String?) {
        mutex.withLock { active.remove(axis); anyMoving = active.isNotEmpty() }
        onAxisStopped(axis)
        reason?.let(onEvent)
        val name = if (axis == Axis.AZM) "de acimut" else "de altitud"
        try {
            mount.stop(axis)
            if (mutex.withLock { pendingStop.remove(axis) }) onEvent("Eje $name parado")
        } catch (e: Exception) {
            // Retried every ~250 ms until it works: say it once, not on every attempt.
            val first = mutex.withLock { pendingStop.add(axis) }
            ensureWatchdog()
            if (first) onEvent("¡No se pudo parar el eje $name (${e.message})! Reintentando…")
        }
    }

    companion object {
        /** Rate 8 (~2°/s) across the slack, rate 6 for the last [TAKE_UP_SLOW_DEG]. */
        const val TAKE_UP_FAST_RATE = 8
        const val TAKE_UP_SLOW_RATE = 6
        const val TAKE_UP_SLOW_DEG = 0.4
        const val TAKE_UP_TOLERANCE_DEG = 0.01
        /** Encoder travel that shows which way the motor really goes. */
        const val TAKE_UP_SEEN_DEG = 0.01
        const val TAKE_UP_POLL_MS = 20L
        const val TAKE_UP_STALL_MS = 1_500L
        const val TAKE_UP_MAX_MS = 8_000L
    }
}

/** How much motor travel the gear slack still needs, for the encoder direction seen (+1/−1). */
fun interface TakeUp {
    fun needed(encoderDir: Int): Double
}