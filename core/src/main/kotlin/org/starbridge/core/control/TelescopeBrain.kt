package org.starbridge.core.control

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.starbridge.core.astro.Astro
import org.starbridge.core.astro.Planets
import org.starbridge.core.astro.Site
import org.starbridge.core.backlash.CenteringSlack
import org.starbridge.core.backlash.GearPlay
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.catalog.CatalogObject
import org.starbridge.core.catalog.Category
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.Direction
import org.starbridge.core.mount.MountDriver
import org.starbridge.core.mount.MountInfo
import org.starbridge.core.mount.RaDec
import org.starbridge.core.mount.TrackingMode
import org.starbridge.core.pointing.AlignmentPoint
import org.starbridge.core.pointing.AlignmentSolution
import org.starbridge.core.pointing.AlignmentSolver
import org.starbridge.core.pointing.LevelFit
import org.starbridge.core.pointing.LevelResult
import org.starbridge.core.pointing.LevelSample
import org.starbridge.core.pointing.PointingParams
import kotlin.math.abs
import kotlin.math.sign

/** Who does the pointing maths. See docs/PLAN_CEREBRO.md. */
enum class PointingMode { HAND_CONTROL, STARBRIDGE }

/** Tracking speed for "follow what I am pointing at": the stars, the Moon or the Sun. */
enum class TrackRate(val key: String, val label: String, val raHoursPerHour: Double) {
    SIDEREAL("sidereal", "sideral", 0.0),
    /** The Moon drifts ~13.2°/day east among the stars. */
    LUNAR("lunar", "lunar", 24.0 / 27.321661 / 24.0),
    /** The Sun drifts ~0.99°/day. */
    SOLAR("solar", "solar", 24.0 / 365.2422 / 24.0);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** What the last tracking tick saw and sent, for the live charts. */
data class TrackTelemetry(val at: Long, val errAzArcsec: Double, val errAltArcsec: Double, val rateAz: Double, val rateAlt: Double)

/**
 * How a StarBridge-mode GoTo drives the motors.
 * [HAND_CONTROL]: the hand control's own Az/Alt GoTo (`b`). [SOFTWARE]: StarBridge closes the
 * loop itself with fixed-rate slews while reading the encoders (works even if an unaligned
 * hand control ignores `b`). [AUTO]: `b` first; if the motors do not move, software from then on.
 */
enum class GotoMethod(val key: String) {
    AUTO("auto"), HAND_CONTROL("hc"), SOFTWARE("soft");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

enum class SiteSource { GPS, MANUAL, HAND_CONTROL, DEFAULT }

/** Where the phone strapped to the tube says it is pointing (true north, apparent altitude). */
fun interface OrientationSensor {
    fun tubePointing(): AltAz?
}

data class Pointing(
    /** Apparent topocentric direction of the tube, null if unknown (not aligned). */
    val sky: AltAz?,
    /** Equinox-of-date coordinates, null if unknown. */
    val raDec: RaDec?,
    /** What the hand control reports (raw encoders when unaligned). */
    val reported: AltAz,
)

data class AlignmentSuggestion(val obj: CatalogObject, val sky: AltAz, val separationDeg: Double?)

/**
 * The "brain": site and time, pointing model, GoTo and tracking. The hand control is only a
 * motor controller in [PointingMode.STARBRIDGE]; in [PointingMode.HAND_CONTROL] it keeps
 * doing its own alignment, GoTo and tracking as before.
 */
class TelescopeBrain(
    private val scope: CoroutineScope,
    private val catalog: Catalog,
    /** UTC wall clock, GPS-corrected when available. */
    private val clock: () -> Long,
    private val onEvent: (String) -> Unit = {},
    /** True while a hold-to-move slew is active (tracking pauses). */
    private val isManualActive: suspend () -> Boolean = { false },
    private val trackingPeriodMs: Long = 1000,
) {
    @Volatile private var mount: MountDriver? = null
    @Volatile var info: MountInfo? = null
        private set
    @Volatile var hcAligned = false
    /** The tracking mode the hand control used last (EQ on a wedge or an equatorial mount). */
    @Volatile private var hcTrackingMode: TrackingMode? = null
        private set
    @Volatile var mode = PointingMode.STARBRIDGE
        private set

    @Volatile var site: Site = DEFAULT_SITE
        private set
    @Volatile var siteSource = SiteSource.DEFAULT
        private set
    @Volatile var gpsAccuracyM: Double? = null
        private set
    private var hcClockWritten = false

    @Volatile var orientationSensor: OrientationSensor? = null

    /** Degrees before the target where the GoTo's first leg stops (anti-backlash approach). */
    @Volatile var approachDeg = 0.5

    @Volatile var gotoMethod = GotoMethod.AUTO

    /** Learned per connection in [GotoMethod.AUTO]: does the hand control execute `b`? */
    @Volatile var handControlGotoWorks: Boolean? = null
        private set

    /**
     * Gear play per axis: the total slack (measured with the gyroscope, the eyepiece or set by
     * hand) and where the motor stands inside it, followed from every encoder reading.
     */
    private val play = mapOf(Axis.AZM to GearPlay(wraps = true), Axis.ALT to GearPlay(wraps = false))

    /** On a reversal with the arrows, first take up the whole slack at speed (Más → Holgura). */
    @Volatile var manualTakeUp = true

    fun setSlack(axis: Axis, degrees: Double) {
        require(degrees in 0.0..3.0) { "Holgura entre 0 y 3 grados" }
        play.getValue(axis).slack = degrees
    }

    fun slack(axis: Axis): Double = play.getValue(axis).slack

    /** Where the motor is inside the play (encoder − tube), for the diagnostics. */
    fun gearOffset(axis: Axis): Double? = play.getValue(axis).takeIf { it.known }?.offset

    /** Encoder angles -> where the tube really is, in the mount frame. */
    private fun encoderToTube(enc: AltAz) = AltAz(
        play.getValue(Axis.AZM).tube(enc.azDeg),
        play.getValue(Axis.ALT).tube(NexStarAlt.signed(enc.altDeg)),
    )

    /** Encoder angles needed for the tube to be at [tube] after moving in the given directions. */
    private fun tubeToEncoder(tube: AltAz, dirAz: Int, dirAlt: Int) = AltAz(
        Astro.norm360(play.getValue(Axis.AZM).encoderFor(tube.azDeg, dirAz)),
        play.getValue(Axis.ALT).encoderFor(tube.altDeg, dirAlt),
    )

    /** Serializes the brain's encoder reads so the gear play sees them in the order taken. */
    private val readLock = Mutex()

    /** Reads the encoders and feeds the gear play model. All brain reads go through here. */
    private suspend fun readEncoders(m: MountDriver): AltAz = readLock.withLock {
        m.getAltAz().also { enc ->
            lastReported?.let { prev ->
                noteDirection(Axis.AZM, PointingParams.angleDiff(enc.azDeg, prev.azDeg))
                noteDirection(Axis.ALT, NexStarAlt.signed(enc.altDeg) - NexStarAlt.signed(prev.altDeg))
            }
            lastReported = enc
            play.getValue(Axis.AZM).observe(enc.azDeg)
            play.getValue(Axis.ALT).observe(NexStarAlt.signed(enc.altDeg))
        }
    }

    /** Encoder direction of the last movement seen on each axis (+1/−1), whatever moved it. */
    private val lastMove = java.util.concurrent.ConcurrentHashMap<Axis, Int>()

    private fun noteDirection(axis: Axis, delta: Double) {
        if (abs(delta) > MOVE_NOISE_DEG) lastMove[axis] = if (delta > 0) 1 else -1
    }

    /**
     * The star [obj] is centred: one centring of the slack measurement ([CenteringSlack]).
     * Nothing else may move the motors meanwhile, or "the last movement" would not be the user's.
     */
    suspend fun centeringMark(obj: CatalogObject): CenteringSlack.Mark {
        val m = requireMount()
        if (tracking) throw IllegalStateException("Apaga el seguimiento mientras mides la holgura")
        if (gotoActive) throw IllegalStateException("Espera a que termine el GoTo")
        val enc = readEncoders(m)
        val now = clock()
        val sky = skyOf(obj, now)
        if (sky.altDeg < 0) throw IllegalStateException("${obj.id} está bajo el horizonte")
        // Without an alignment the base is taken as level: over a few minutes the difference is negligible.
        val star = solution?.params?.skyToMount(sky) ?: sky
        return CenteringSlack.Mark(
            AltAz(enc.azDeg, NexStarAlt.signed(enc.altDeg)), star,
            lastMove[Axis.AZM] ?: 0, lastMove[Axis.ALT] ?: 0, now,
        )
    }

    /** The hand-control arrow ("pos"/"neg" command) that moves [axis] in [encoderDir]. */
    fun commandFor(axis: Axis, encoderDir: Int): Int = encoderDir * motorSign.getValue(axis)

    /**
     * The encoder direction of each axis a GoTo to [obj] arrives in right now: the way the sky
     * carries it (see [runStarBridgeGoto]), so tracking starts with the gears already loaded.
     */
    fun arrivalDirections(obj: CatalogObject): Pair<Int, Int> {
        val now = clock()
        val p = solution?.params
        val a = skyOf(obj, now).let { p?.skyToMount(it) ?: it }
        val b = skyOf(obj, now + 60_000).let { p?.skyToMount(it) ?: it }
        return (if (PointingParams.angleDiff(b.azDeg, a.azDeg) < 0) -1 else 1) to (if (b.altDeg - a.altDeg < 0) -1 else 1)
    }

    /**
     * Axes where the object was centred with the last movement against the GoTo arrival: there
     * the recorded point depends on the slack value being exact. Value = the arrow (command)
     * to finish with instead. Empty when the centring is consistent with the GoTo.
     */
    fun finishAgainstArrival(obj: CatalogObject): Map<Axis, Int> {
        val (dAz, dAlt) = arrivalDirections(obj)
        val out = mutableMapOf<Axis, Int>()
        lastMove[Axis.AZM]?.let { if (it != dAz) out[Axis.AZM] = commandFor(Axis.AZM, dAz) }
        lastMove[Axis.ALT]?.let { if (it != dAlt) out[Axis.ALT] = commandFor(Axis.ALT, dAlt) }
        return out
    }

    /**
     * The take-up to do before a manual slew in [direction] (motor command): how much encoder
     * travel, given the encoder direction it really turns out to move in. Null: nothing to do.
     */
    fun takeUpFor(axis: Axis, direction: Direction): TakeUp? {
        if (!manualTakeUp) return null
        // Only with a known motor direction: rushing the wrong way would jump the tube.
        if (motorConfirmed[axis] != true) return null
        val p = play.getValue(axis)
        val encoderDir = (if (direction == Direction.POSITIVE) 1 else -1) * motorSign.getValue(axis)
        if (p.needed(encoderDir) < MIN_TAKE_UP_DEG) return null
        return TakeUp { observedDir -> p.needed(observedDir) }
    }

    // Alignment
    @Volatile var points: List<AlignmentPoint> = emptyList()
        private set
    @Volatile var solution: AlignmentSolution? = null
        private set
    /** Encoders may have been reset (mount power-cycled) since the alignment was made. */
    @Volatile var alignmentNeedsCheck = false
        private set

    // GoTo / tracking
    @Volatile private var gotoJob: Job? = null
    /** A GoTo cancelled by STOP that may still be sending its last stops. */
    @Volatile private var haltedJob: Job? = null
    @Volatile private var trackingJob: Job? = null
    @Volatile var tracking = false
        private set
    @Volatile var trackingTarget: RaDec? = null
        private set
    @Volatile var trackingLabel: String? = null
        private set
    @Volatile private var retarget = false
    @Volatile private var lastTrackingError = 0L
    @Volatile private var trackFailures = 0

    /**
     * Bumped by STOP, tracking off, alignment changes and manual slews. A tracking tick that
     * started under an older generation must not send anything.
     */
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    /** Makes "check that tracking may move the motors" + "send the rates" atomic. */
    private val sendLock = Mutex()

    /** Wall time of the last manual slew start: tracking stays quiet right after it. */
    @Volatile private var manualSlewAt = 0L

    /** +1 if a POSITIVE motor command increases the encoder angle, −1 if not (learned). */
    private val motorSign = java.util.concurrent.ConcurrentHashMap(mapOf(Axis.AZM to 1, Axis.ALT to 1))
    /** [motorSign] has been seen to be right on this connection (a manual move or a GoTo). */
    private val motorConfirmed = java.util.concurrent.ConcurrentHashMap<Axis, Boolean>()
    private val manualStart = java.util.concurrent.ConcurrentHashMap<Axis, Double>()
    private val manualCommand = java.util.concurrent.ConcurrentHashMap<Axis, Int>()
    @Volatile private var lastReported: AltAz? = null
    @Volatile private var gpsPrecise = false

    val gotoActive: Boolean get() = gotoJob?.isActive == true

    // --- Connection ----------------------------------------------------------

    suspend fun attach(driver: MountDriver, mountInfo: MountInfo) {
        mount = driver
        info = mountInfo
        hcClockWritten = false
        handControlGotoWorks = null
        motorConfirmed.clear()
        lastMove.clear()
        lastReported = null
        play.values.forEach { it.reset() } // the motors may have been moved or power-cycled
        runCatching { readEncoders(driver) } // the starting point every later move is measured from
        hcAligned = runCatching { driver.isAligned() }.getOrDefault(false)
        hcTrackingMode = null
        // Equatorial mounts always use the hand control's own alignment (see MountInfo.isEquatorial).
        mode = if (hcAligned || mountInfo.isEquatorial) PointingMode.HAND_CONTROL else PointingMode.STARBRIDGE
        if (mountInfo.isEquatorial && !hcAligned) {
            onEvent("Montura ecuatorial (${mountInfo.modelName}): alinéala con el mando; StarBridge usará su alineación")
        }
        if (points.isNotEmpty()) alignmentNeedsCheck = true
        if (siteSource == SiteSource.DEFAULT) {
            runCatching { driver.getSite() }.getOrNull()
                ?.takeIf { abs(it.latitudeDeg) > 0.01 || abs(it.longitudeDeg) > 0.01 }
                ?.let { site = it; siteSource = SiteSource.HAND_CONTROL }
        }
        if (mode == PointingMode.STARBRIDGE) {
            runCatching { driver.setTracking(TrackingMode.OFF) }
            tracking = false
        } else {
            // The hand control may already be tracking: reflect it (and remember how).
            val hcMode = runCatching { driver.getTracking() }.getOrNull()
            hcMode?.takeIf { it != TrackingMode.OFF }?.let { hcTrackingMode = it }
            tracking = hcMode?.let { it != TrackingMode.OFF } ?: false
        }
        writeHandControlClock()
        startTrackingLoop()
    }

    /** The mount is going away: stop everything and let a running GoTo send its last stops. */
    suspend fun detach() {
        generation.incrementAndGet()
        trackingJob?.cancel()
        tracking = false
        val jobs = listOfNotNull(gotoJob, haltedJob)
        gotoJob = null
        haltedJob = null
        jobs.forEach { it.cancel() }
        withTimeoutOrNull(DETACH_WAIT_MS) { jobs.forEach { it.join() } }
        mount = null
        info = null
    }

    private fun requireMount() = mount ?: throw IllegalStateException("Telescopio no conectado")

    suspend fun setMode(newMode: PointingMode) {
        val m = requireMount()
        if (newMode == mode) return
        if (newMode == PointingMode.STARBRIDGE && info?.isEquatorial == true) {
            throw IllegalStateException("En una montura ecuatorial la alineación es la del mando")
        }
        cancelGotoJob()
        stopTracking(sendStop = true)
        if (newMode == PointingMode.HAND_CONTROL) {
            hcAligned = runCatching { m.isAligned() }.getOrDefault(false)
            if (!hcAligned) onEvent("El mando no está alineado: alinéalo con el mando para usar este modo")
        } else {
            runCatching { m.setTracking(TrackingMode.OFF) }
        }
        mode = newMode
    }

    // --- Site & time -----------------------------------------------------------

    /** [precise] = a real satellite fix (its time is used); false for network positions. */
    suspend fun updateGps(newSite: Site, accuracyM: Double?, precise: Boolean = true) {
        site = newSite
        siteSource = SiteSource.GPS
        gpsAccuracyM = accuracyM
        if (precise) gpsPrecise = true
        writeHandControlClock()
    }

    fun setManualSite(newSite: Site) {
        if (siteSource == SiteSource.GPS) {
            onEvent("Hay GPS: se usará la posición del GPS")
            return
        }
        site = newSite
        siteSource = SiteSource.MANUAL
    }

    /** The hand control also benefits from the GPS: write site and time once per connection. */
    private suspend fun writeHandControlClock() {
        val m = mount ?: return
        val i = info ?: return
        if (hcClockWritten || siteSource != SiteSource.GPS || !gpsPrecise || hcAligned || !i.handControlVersion.atLeast(2, 3)) return
        try {
            m.setSite(site)
            m.setDateTime(clock(), java.time.ZoneId.systemDefault())
            hcClockWritten = true
            onEvent("Hora y lugar del GPS enviados al mando")
        } catch (e: Exception) {
            onEvent("No se pudo escribir hora/lugar en el mando: ${e.message}")
        }
    }

    // --- Where are we pointing? -------------------------------------------------

    suspend fun pointing(): Pointing {
        val m = requireMount()
        val reported = readEncoders(m)
        val now = clock()
        val sky: AltAz? = when (mode) {
            PointingMode.HAND_CONTROL -> if (hcAligned) reported else null
            PointingMode.STARBRIDGE -> solution?.params?.mountToSky(encoderToTube(reported))
        }
        return Pointing(sky, sky?.let { Astro.apparentToRaDec(it, site, now) }, reported)
    }

    fun skyOf(obj: CatalogObject, epochMillis: Long = clock()): AltAz =
        Astro.apparentAltAz(obj.position(epochMillis, site), site, epochMillis)

    // --- GoTo --------------------------------------------------------------------

    suspend fun goto(obj: CatalogObject) {
        val m = requireMount()
        val now = clock()
        val sky = skyOf(obj, now)
        if (sky.altDeg < MIN_GOTO_ALT) throw IllegalStateException("${obj.id} está bajo el horizonte")
        if (obj.body == Planets.Body.SUN) throw IllegalStateException("Nunca apuntes al Sol")
        checkAwayFromSun(sky, obj.id, now)
        cancelGotoJob() // waits: the old job's cleanup must not cancel the new GoTo
        stopTracking(sendStop = false)
        when (mode) {
            PointingMode.HAND_CONTROL -> {
                // Aligned on the hand control since we connected (equatorial mounts start here)?
                if (!hcAligned) hcAligned = runCatching { m.isAligned() }.getOrDefault(false)
                if (!hcAligned) throw IllegalStateException("El mando no está alineado")
                m.gotoRaDec(obj.position(now, site))
                onEvent("GoTo ${obj.displayName} (mando)")
            }
            PointingMode.STARBRIDGE -> {
                val params = solution?.params ?: throw IllegalStateException("Primero alinea el telescopio")
                gotoJob = scope.launch {
                    runStarBridgeGoto(m, obj.displayName, { t -> params.skyToMount(skyOf(obj, t)) }) {
                        startTracking(obj.position(clock(), site), obj.displayName, fixedTarget = obj)
                    }
                }
            }
        }
    }

    /** GoTo to catalog-style J2000 coordinates typed by hand; tracks them on arrival. */
    suspend fun gotoCoordinates(raHours: Double, decDeg: Double, label: String?) {
        require(raHours in 0.0..24.0 && decDeg in -90.0..90.0) { "Coordenadas no válidas" }
        val name = label?.takeIf { it.isNotBlank() } ?: "AR ${"%.3f".format(raHours)}h Dec ${"%+.2f".format(decDeg)}°"
        goto(CatalogObject(name, "", "Coordenadas", org.starbridge.core.catalog.Category.OTHER, RaDec(raHours, decDeg), null, ""))
    }

    /** GoTo to a fixed direction of the sky (azimuth/altitude); no tracking afterwards. */
    suspend fun gotoHorizontal(target: AltAz) {
        val m = requireMount()
        require(target.altDeg in MIN_GOTO_ALT..90.0) { "La altura debe estar entre $MIN_GOTO_ALT y 90°" }
        checkAwayFromSun(target, "Esa dirección", clock())
        if (mode != PointingMode.STARBRIDGE) throw IllegalStateException("Solo en modo StarBridge")
        val params = solution?.params ?: throw IllegalStateException("Primero alinea el telescopio")
        cancelGotoJob()
        stopTracking(sendStop = false)
        val label = "Az ${"%.1f".format(target.azDeg)}° Alt ${"%.1f".format(target.altDeg)}°"
        gotoJob = scope.launch { runStarBridgeGoto(m, label, { params.skyToMount(target) }) {} }
    }

    /**
     * Back to the power-on position (encoders 0, 0): park it there and switch off; next time
     * it starts from the same place.
     */
    suspend fun park() {
        val m = requireMount()
        if (mode != PointingMode.STARBRIDGE) throw IllegalStateException("En modo mando, aparca desde el mando")
        cancelGotoJob()
        stopTracking(sendStop = true)
        gotoJob = scope.launch {
            try {
                onEvent("Aparcando en la posición de encendido…")
                slewTo(m, AltAz(0.0, 0.0), finalLeg = true)
                onEvent("Aparcado: ya puedes apagar el telescopio")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onEvent("No se pudo aparcar: ${e.message}")
            }
        }
    }

    private val sunObject = CatalogObject("Sol", "Sol", "Estrella", org.starbridge.core.catalog.Category.STAR, null, -26.7, "", Planets.Body.SUN)

    /** With the Sun up, never point anywhere near it: a telescope focuses it into an eye. */
    private fun checkAwayFromSun(target: AltAz, what: String, now: Long) {
        val sun = skyOf(sunObject, now)
        if (sun.altDeg > -2 && PointingParams.separation(target, sun) < SUN_GUARD_DEG) {
            throw IllegalStateException("$what está a menos de ${SUN_GUARD_DEG.toInt()}° del Sol, que está sobre el horizonte")
        }
    }

    private suspend fun runStarBridgeGoto(
        m: MountDriver,
        label: String,
        tubeTarget: (Long) -> AltAz,
        onArrive: suspend () -> Unit,
    ) {
        try {
            onEvent("GoTo $label")
            // Arrive moving in the direction the tracking will move, so the gears stay loaded
            // on the right side and tracking starts without a dead zone.
            val start = readEncoders(m)
            val first = tubeTarget(clock())
            val travelS = maxOf(
                abs(PointingParams.angleDiff(first.azDeg, start.azDeg)),
                abs(first.altDeg - start.altDeg),
            ) / 3.0 + 5
            val tArrive = clock() + (travelS * 1000).toLong()
            val a = tubeTarget(tArrive)
            val b = tubeTarget(tArrive + 60_000)
            val dirAz = if (PointingParams.angleDiff(b.azDeg, a.azDeg) < 0) -1 else 1
            val dirAlt = if (b.altDeg - a.altDeg < 0) -1 else 1

            val pre = AltAz(Astro.norm360(a.azDeg - dirAz * approachDeg), a.altDeg - dirAlt * approachDeg)
            slewTo(m, tubeToEncoder(pre, -dirAz, -dirAlt), finalLeg = false)
            // The last approach (approachDeg + the whole slack, in the tracking direction) loads
            // the gears on the right side whatever the first leg did.
            slewTo(m, tubeToEncoder(tubeTarget(clock() + 2_000), dirAz, dirAlt), finalLeg = true)
            onEvent("Llegado a $label")
            onArrive()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { m.cancelGoto() } }
            throw e
        } catch (e: org.starbridge.core.transport.DiscardedByStopException) {
            // A STOP overtook one of our slews. If it cancelled this GoTo the user stopped it;
            // otherwise the GoTo really did not run and the user must know.
            if (kotlin.coroutines.coroutineContext.isActive) onEvent("GoTo interrumpido por un STOP")
        } catch (e: Exception) {
            onEvent("GoTo fallido: ${e.message}")
            runCatching { m.cancelGoto() }
        }
    }

    /**
     * Moves the encoders to [target] with the hand control's GoTo or StarBridge's own loop.
     * In [GotoMethod.AUTO] the [finalLeg] is always StarBridge's: the hand control has its own
     * "GoTo approach" direction and may finish moving the other way, which would leave the
     * gears loaded on the wrong side (an error of the whole slack on the sky).
     */
    private suspend fun slewTo(m: MountDriver, target: AltAz, finalLeg: Boolean) {
        val useHandControl = when (gotoMethod) {
            GotoMethod.SOFTWARE -> false
            GotoMethod.HAND_CONTROL -> true
            GotoMethod.AUTO -> handControlGotoWorks != false && !finalLeg
        }
        if (!useHandControl) return softwareGoto(m, target)
        val start = readEncoders(m)
        m.gotoAltAz(target)
        if (gotoMethod == GotoMethod.AUTO && handControlGotoWorks == null && encoderDistance(start, target) > MIN_VERIFY_DEG) {
            if (!handControlGotoStarted(m, start)) {
                // Old hand controls may ignore `b` until they are aligned: drive the motors ourselves.
                handControlGotoWorks = false
                runCatching { m.cancelGoto() }
                onEvent("El mando no ejecuta el GoTo: StarBridge mueve los motores directamente")
                return softwareGoto(m, target)
            }
            handControlGotoWorks = true
        }
        waitForGoto(m)
        // Stopped short: aborted on the keypad, a slew limit, or `b` read in another frame. Never
        // finish it on our own at full speed (the user may have stopped it on purpose): say so.
        val off = encoderDistance(readEncoders(m), target)
        if (off > MAX_HC_ARRIVAL_DEG) {
            throw IllegalStateException(
                "el mando se detuvo a ${"%.1f".format(off)}° del objetivo. Si se repite, elige Más → GoTo → StarBridge",
            )
        }
    }

    /** True once the hand control reports a GoTo in progress or the encoders move. */
    private suspend fun handControlGotoStarted(m: MountDriver, start: AltAz): Boolean {
        repeat((HC_GOTO_VERIFY_MS / VERIFY_POLL_MS).toInt()) {
            delay(VERIFY_POLL_MS)
            if (runCatching { m.isGotoInProgress() }.getOrDefault(false)) return true
            if (encoderDistance(readEncoders(m), start) > MIN_MOVE_DEG) return true
        }
        return false
    }

    private fun encoderError(axis: Axis, target: AltAz, now: AltAz) =
        if (axis == Axis.AZM) PointingParams.angleDiff(target.azDeg, now.azDeg)
        else NexStarAlt.signed(Astro.norm360(target.altDeg)) - NexStarAlt.signed(now.altDeg)

    private fun encoderDistance(a: AltAz, b: AltAz) =
        maxOf(abs(encoderError(Axis.AZM, a, b)), abs(encoderError(Axis.ALT, a, b)))

    /**
     * Closed-loop GoTo with fixed-rate slews (the same commands as the arrows): each axis slows
     * down as it nears [target] and stops on arrival. Learns the motor direction if it is
     * reversed, and gives up if an encoder does not move. Both axes are always left stopped.
     */
    private suspend fun softwareGoto(m: MountDriver, target: AltAz) {
        val rate = mutableMapOf(Axis.AZM to 0, Axis.ALT to 0) // signed in encoder direction
        val done = mutableMapOf(Axis.AZM to false, Axis.ALT to false)
        val since = mutableMapOf<Axis, Pair<Int, Double>>() // poll count and |error| when the rate was set
        val lastErr = mutableMapOf<Axis, Double>()
        var polls = 0
        var finished = false
        try {
            while (done.values.any { !it }) {
                if (++polls > SOFT_MAX_POLLS) throw IllegalStateException("el GoTo no terminó a tiempo")
                val now = readEncoders(m)
                for (axis in Axis.entries) {
                    if (done.getValue(axis)) continue
                    val err = encoderError(axis, target, now)
                    val prev = lastErr.put(axis, err)
                    val current = rate.getValue(axis)
                    // Near the antipode both ways are as long: keep going instead of flipping
                    // back and forth across the ±180° azimuth wrap.
                    val dir = if (current != 0 && abs(err) > WRAP_KEEP_DEG) current.sign else if (err >= 0) 1 else -1
                    // Arrived, or just went past it (a sign flip close by; far away it is the
                    // ±180° azimuth wrap of a motor going the wrong way, caught below).
                    val passed = current != 0 && dir != current.sign && prev != null && abs(prev) < OVERSHOOT_MAX_DEG
                    if (abs(err) < SOFT_TOLERANCE_DEG || passed) {
                        if (current != 0) m.stop(axis)
                        rate[axis] = 0
                        done[axis] = true
                        continue
                    }
                    val (sincePoll, sinceErr) = since[axis] ?: (polls to abs(err))
                    if (current != 0 && polls - sincePoll >= SOFT_CHECK_POLLS) {
                        when {
                            // Moving away from the target: this motor is reversed.
                            abs(err) > sinceErr + SOFT_WRONG_WAY_DEG -> {
                                motorSign[axis] = -motorSign.getValue(axis)
                                motorConfirmed[axis] = true
                                rate[axis] = 0
                                onEvent("Sentido del motor de ${axisLabel(axis)} corregido")
                            }
                            abs(sinceErr - abs(err)) < MIN_MOVE_DEG ->
                                throw IllegalStateException("el motor de ${axisLabel(axis)} no se mueve")
                            else -> motorConfirmed[axis] = true
                        }
                        since[axis] = polls to abs(err)
                    }
                    // Until this motor's direction is known, never full speed the wrong way.
                    val cap = if (motorConfirmed[axis] == true) 9 else UNCONFIRMED_MAX_RATE
                    val wanted = dir * minOf(softRate(abs(err)), cap)
                    if (wanted != rate.getValue(axis)) {
                        val motorDir = if (dir * motorSign.getValue(axis) > 0) Direction.POSITIVE else Direction.NEGATIVE
                        // Recorded first: if this coroutine is cancelled while the command is on
                        // the wire, the finally block must still stop the axis.
                        rate[axis] = wanted
                        since[axis] = polls to abs(err)
                        m.slew(axis, motorDir, abs(wanted))
                    }
                }
                if (done.values.any { !it }) delay(SOFT_POLL_MS)
            }
            finished = true
        } finally {
            withContext(NonCancellable) {
                // Cancelled or failed: stop both axes, whatever we think they are doing.
                Axis.entries.filter { !finished || rate.getValue(it) != 0 }.forEach { runCatching { m.stop(it) } }
            }
        }
    }

    private fun axisLabel(axis: Axis) = if (axis == Axis.AZM) "acimut" else "altitud"

    /** Fixed rate for a remaining distance: fast far away, fine close by (overshoot < 0.03°). */
    private fun softRate(distanceDeg: Double) = when {
        distanceDeg > 8.0 -> 9
        distanceDeg > 3.0 -> 8
        distanceDeg > 1.2 -> 7
        distanceDeg > 0.4 -> 6
        distanceDeg > 0.12 -> 5
        else -> 4
    }

    private suspend fun waitForGoto(m: MountDriver) {
        val done = withTimeoutOrNull(GOTO_TIMEOUT_MS) {
            delay(300)
            while (m.isGotoInProgress()) delay(300)
            true
        }
        if (done == null) throw IllegalStateException("el GoTo no terminó a tiempo")
    }

    private suspend fun cancelGotoJob() {
        val job = gotoJob
        gotoJob = null
        job?.cancelAndJoin()
        // A GoTo halted by STOP may still be sending its final stops: they must not land on
        // (and cancel) the next GoTo.
        haltedJob?.join()
        haltedJob = null
    }

    suspend fun cancelGoto() {
        cancelGotoJob()
        stopTracking(sendStop = false)
        mount?.cancelGoto()
    }

    /** Called by STOP (before the stop commands): nothing may restart the motors afterwards. */
    fun emergencyHalt() {
        generation.incrementAndGet()
        val job = gotoJob
        gotoJob = null
        job?.cancel() // not joined: the STOP itself must not wait (the next GoTo will)
        if (job != null) haltedJob = job
        tracking = false
        trackingTarget = null
        trackingLabel = null
    }

    // --- Tracking ----------------------------------------------------------------

    @Volatile private var trackedObject: CatalogObject? = null

    /** Speed when following the current position (not an object): stars, Moon or Sun. */
    @Volatile var trackRate = TrackRate.SIDEREAL
        private set
    /** When [trackingTarget] was taken: the lunar/solar drift counts from here. */
    @Volatile private var trackSince = 0L

    /** Last tick's errors and speeds, for the live charts (null when not tracking). */
    @Volatile var trackTelemetry: TrackTelemetry? = null
        private set

    /** Starts tracking [target] (equinox of date), or the current pointing if null. */
    suspend fun startTracking(
        target: RaDec? = null,
        label: String? = null,
        fixedTarget: CatalogObject? = null,
        rate: TrackRate = TrackRate.SIDEREAL,
    ) {
        val m = requireMount()
        if (mode == PointingMode.HAND_CONTROL) {
            m.setTracking(handControlTrackingMode(m))
            tracking = true
            trackingLabel = label
            return
        }
        val t = target ?: pointing().raDec ?: throw IllegalStateException("Primero alinea el telescopio")
        trackedObject = fixedTarget?.takeIf { it.body != null } // planets/Moon move: recompute
        trackingTarget = t
        trackRate = if (target == null) rate else TrackRate.SIDEREAL
        trackSince = clock()
        trackTelemetry = null
        trackingLabel = label
        retarget = false
        trackFailures = 0
        trackDir.clear() // picked from the sky motion on the first tick
        sentRate.clear()
        tracking = true
    }

    /** Alt-az, unless the hand control tracks equatorially (wedge, equatorial mount). */
    private suspend fun handControlTrackingMode(m: MountDriver): TrackingMode {
        runCatching { m.getTracking() }.getOrNull()?.takeIf { it != TrackingMode.OFF }?.let { hcTrackingMode = it }
        if (info?.isEquatorial != true) return hcTrackingMode ?: TrackingMode.ALT_AZ
        return hcTrackingMode?.takeIf { it != TrackingMode.ALT_AZ }
            ?: if (site.latitudeDeg >= 0) TrackingMode.EQ_NORTH else TrackingMode.EQ_SOUTH
    }

    suspend fun stopTracking(sendStop: Boolean) {
        generation.incrementAndGet()
        val wasTracking = tracking
        tracking = false
        trackingTarget = null
        trackingLabel = null
        trackedObject = null
        val m = mount ?: return
        if (mode == PointingMode.HAND_CONTROL) {
            // Always: the hand control may be tracking even if we did not start it.
            if (sendStop) runCatching { m.setTracking(TrackingMode.OFF) }
        } else if (wasTracking && sendStop && !isManualActive()) {
            Axis.entries.forEach { runCatching { m.stop(it) } }
        }
    }

    /**
     * A hold-to-move slew is about to start. Cancels a running GoTo (it would fight the user),
     * keeps tracking quiet, and prepares to learn the real encoder direction of this axis.
     */
    suspend fun onManualSlew(axis: Axis, direction: Direction) {
        if (gotoActive) {
            cancelGotoJob()
            onEvent("GoTo cancelado: has movido el telescopio")
        }
        val cmd = if (direction == Direction.POSITIVE) 1 else -1
        // A fresh reading right before the motor starts: the gear play must see every reversal.
        val before = mount?.let { m -> runCatching { readEncoders(m) }.getOrNull() } ?: lastReported
        sendLock.withLock {
            generation.incrementAndGet()
            manualSlewAt = clock()
            manualCommand[axis] = cmd
            before?.let { manualStart[axis] = if (axis == Axis.AZM) it.azDeg else NexStarAlt.signed(it.altDeg) }
            if (tracking) retarget = true
        }
    }

    /** The button was released: read where the motor ended and learn which way it went. */
    suspend fun onManualStop(axis: Axis) {
        val now = mount?.let { m -> runCatching { readEncoders(m) }.getOrNull() } ?: return
        val start = manualStart.remove(axis) ?: return
        val cmd = manualCommand.remove(axis) ?: return
        val delta = if (axis == Axis.AZM) PointingParams.angleDiff(now.azDeg, start) else NexStarAlt.signed(now.altDeg) - start
        if (abs(delta) < MIN_LEARN_DEG) return
        val encDir = if (delta > 0) 1 else -1
        val sign = encDir * cmd
        motorConfirmed[axis] = true
        if (motorSign.put(axis, sign) != sign) {
            onEvent("Sentido del motor de ${if (axis == Axis.AZM) "acimut" else "altitud"} detectado")
        }
    }

    /** A calibration moved [axis] on its own and left the gears loaded in [encoderDir]. */
    fun noteMovement(axis: Axis, encoderDir: Int) {
        play.getValue(axis).load(if (encoderDir > 0) 1 else if (encoderDir < 0) -1 else 0)
    }

    private fun startTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = scope.launch {
            while (isActive) {
                delay(trackingPeriodMs)
                if (mode != PointingMode.STARBRIDGE || !tracking || gotoActive) continue
                try {
                    trackOnce()
                    trackFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val now = clock()
                    if (now - lastTrackingError > 30_000) onEvent("Seguimiento: ${e.message}")
                    lastTrackingError = now
                    // Never leave the last rate running without corrections.
                    if (++trackFailures >= MAX_TRACK_FAILURES) {
                        onEvent("Seguimiento detenido: el telescopio no responde")
                        stopTracking(sendStop = true)
                    }
                }
            }
        }
    }

    private suspend fun trackOnce() {
        val m = mount ?: return
        val gen = generation.get()
        val params = solution?.params ?: run {
            stopTracking(sendStop = true) // alignment removed while tracking
            return
        }
        if (isManualActive() || clock() - manualSlewAt < MANUAL_QUIET_MS) {
            retarget = true
            return
        }
        if (retarget) {
            retarget = false
            trackedObject = null
            trackingTarget = pointing().raDec ?: return
            trackSince = clock()
        }
        val have = readEncoders(m)
        val now = clock() // just after the reading: the error below is the error at this instant
        val fixed = trackingTarget
        val moving = trackedObject
        val drift = trackRate.raHoursPerHour
        val since = trackSince
        fun tubeAt(t: Long): AltAz? {
            // Lunar/solar: the followed point creeps east among the stars.
            val radec = moving?.position(t, site)
                ?: fixed?.let { RaDec((it.raHours + drift * (t - since) / 3_600_000.0).mod(24.0), it.decDeg) }
                ?: return null
            return params.skyToMount(Astro.apparentAltAz(radec, site, t))
        }
        val target = moving?.position(now, site) ?: fixed ?: return
        if (Astro.apparentAltAz(target, site, now + trackingPeriodMs).altDeg < 0) {
            onEvent("${trackingLabel ?: "El objeto"} se ha puesto: seguimiento detenido")
            stopTracking(sendStop = true)
            return
        }
        val tubeNow = tubeAt(now) ?: return
        // Feed-forward: how fast the tube must turn, from the sky motion around now.
        val before = tubeAt(now - TRACK_FF_HALF_MS) ?: return
        val after = tubeAt(now + TRACK_FF_HALF_MS) ?: return
        val span = 2 * TRACK_FF_HALF_MS / 1000.0
        val vAz = PointingParams.angleDiff(after.azDeg, before.azDeg) / span
        val vAlt = (after.altDeg - before.altDeg) / span
        // The gears stay loaded on the side the tube moves; a reversal (altitude at the
        // meridian) takes up the slack through the error term, then the tube moves on.
        val want = tubeToEncoder(tubeNow, trackSide(Axis.AZM, vAz), trackSide(Axis.ALT, vAlt))
        val eAz = PointingParams.angleDiff(want.azDeg, have.azDeg)
        val eAlt = want.altDeg - NexStarAlt.signed(have.altDeg)
        if (abs(eAz) > MAX_TRACK_ERROR_DEG + slack(Axis.AZM) || abs(eAlt) > MAX_TRACK_ERROR_DEG + slack(Axis.ALT)) {
            onEvent("Seguimiento perdido (error ${"%.1f".format(maxOf(abs(eAz), abs(eAlt)))} grados): vuelve a hacer GoTo")
            stopTracking(sendStop = true)
            return
        }
        sendLock.withLock {
            // STOP, tracking off or a manual slew since this tick started: send nothing.
            if (!tracking || generation.get() != gen || isManualActive()) return
            // The sky's own speed plus a gentle pull on what is left (over ~TRACK_TAU_S): no
            // overshoot even with a slow cable, so the speed hardly changes from tick to tick.
            val rAz = (vAz + eAz / TRACK_TAU_S) * 3600
            val rAlt = (vAlt + eAlt / TRACK_TAU_S) * 3600
            sendRate(m, Axis.AZM, rAz, gen)
            sendRate(m, Axis.ALT, rAlt, gen)
            trackTelemetry = TrackTelemetry(now, eAz * 3600, eAlt * 3600, rAz, rAlt)
        }
        // A STOP that arrived while the rates were being sent: make sure they do not survive it.
        if (generation.get() != gen && !isManualActive()) Axis.entries.forEach { runCatching { m.stop(it) } }
    }

    /** Which side of the gear play tracking pushes on: flips only on a clear reversal. */
    private val trackDir = java.util.concurrent.ConcurrentHashMap<Axis, Int>()

    private fun trackSide(axis: Axis, speedDegS: Double): Int {
        val current = trackDir[axis] ?: 0
        val wanted = if (speedDegS >= 0) 1 else -1
        val side = if (current == 0 || (wanted != current && abs(speedDegS) > TRACK_FLIP_DEG_S)) wanted else current
        trackDir[axis] = side
        return side
    }

    /** Last speed sent per axis (encoder arcsec/s) and the generation it was sent under. */
    private val sentRate = java.util.concurrent.ConcurrentHashMap<Axis, Pair<Double, Long>>()
    private val sentAt = java.util.concurrent.ConcurrentHashMap<Axis, Long>()

    private suspend fun sendRate(m: MountDriver, axis: Axis, arcsecPerSec: Double, gen: Long) {
        val wanted = arcsecPerSec.coerceIn(-MAX_TRACK_RATE, MAX_TRACK_RATE)
        // Same speed as last time: do not resend (fewer commands, no needless motor hiccups),
        // but refresh now and then in case one was lost.
        val (last, lastGen) = sentRate[axis] ?: (Double.NaN to -1L)
        val small = abs(wanted - last) <= maxOf(RATE_DEADBAND_ARCSEC, RATE_DEADBAND_FRACTION * abs(wanted))
        if (lastGen == gen && small && clock() - (sentAt[axis] ?: 0L) < RATE_REFRESH_MS) return
        // v is the wanted encoder speed; the motor command sign may be reversed on this axis.
        val v = wanted * motorSign.getValue(axis)
        m.slewVariable(axis, if (v >= 0) Direction.POSITIVE else Direction.NEGATIVE, abs(v))
        sentRate[axis] = wanted to gen
        sentAt[axis] = clock()
    }

    // --- Alignment -----------------------------------------------------------------

    fun alignmentSuggestions(limit: Int = 6): List<AlignmentSuggestion> {
        val now = clock()
        val used = points.filter { !it.fromSensor }
        val candidates = catalog.alignmentStars + catalog.objects.filter {
            it.body in setOf(Planets.Body.VENUS, Planets.Body.JUPITER, Planets.Body.SATURN, Planets.Body.MARS)
        }
        return candidates.asSequence()
            .filter { o -> used.none { it.label == o.id } }
            .map { o ->
                val sky = skyOf(o, now)
                val sep = used.minOfOrNull { PointingParams.separation(it.sky, sky) }
                AlignmentSuggestion(o, sky, sep)
            }
            .filter { it.sky.altDeg in 20.0..75.0 }
            .sortedByDescending { s ->
                val brightness = -(s.obj.magnitude ?: -2.0)
                val spread = s.separationDeg?.let { minOf(it, 90.0) / 30.0 } ?: 0.0
                brightness + spread
            }
            .take(limit)
            .toList()
    }

    data class AddResult(val solution: AlignmentSolution, val warning: String?)

    /** The user has centred [obj] in the eyepiece. */
    suspend fun addAlignmentStar(obj: CatalogObject): AddResult {
        val m = requireMount()
        if (mode == PointingMode.HAND_CONTROL) throw IllegalStateException("En modo mando usa Sync")
        val raw = readEncoders(m)
        val now = clock()
        val sky = skyOf(obj, now)
        if (sky.altDeg < 0) throw IllegalStateException("${obj.id} está bajo el horizonte")
        val warning = solution?.takeIf { !it.sensorOnly && it.starCount > 0 }?.let { sol ->
            val predicted = sol.params.mountToSky(encoderToTube(raw))
            val off = PointingParams.separation(predicted, sky)
            if (off > 10) "El telescopio esperaba estar a ${"%.0f".format(off)}° de ${obj.id}. ¿Seguro que has centrado esa estrella?" else null
        }
        val tube = encoderToTube(AltAz(raw.azDeg, NexStarAlt.signed(raw.altDeg)))
        val point = AlignmentPoint(obj.id, tube, sky, STAR_SIGMA, STAR_SIGMA)
        points = points.filter { it.label != obj.id } + point
        val sol = solve()
        alignmentNeedsCheck = false
        val bad = sol.residualsArcmin.withIndex().filter { it.value > 30 && sol.starCount >= 3 }
        val residualWarning = bad.takeIf { it.isNotEmpty() }?.let {
            "Una estrella encaja mal (${"%.0f".format(it.first().value)}′). Revisa que centraste la correcta."
        }
        // Two stars close together leave the tilt of the base poorly known: far from them it misses.
        val others = points.filter { !it.fromSensor && it.label != obj.id }
        val closest = others.minByOrNull { PointingParams.separation(it.sky, sky) }
        val spreadWarning = closest?.takeIf { others.size == 1 }?.let { c ->
            val sep = PointingParams.separation(c.sky, sky)
            if (sep < CLOSE_STARS_DEG) "${obj.id} está a ${"%.0f".format(sep)}° de ${c.label}: con dos estrellas tan juntas la inclinación de la base sale mal. Añade otra a más de 60° de las dos." else null
        }
        return AddResult(sol, warning ?: residualWarning ?: spreadWarning)
    }

    /** Hand-control mode: refine the hand control's own alignment on a centred object. */
    suspend fun syncHandControl(obj: CatalogObject) {
        val m = requireMount()
        if (mode != PointingMode.HAND_CONTROL) throw IllegalStateException("Sync solo en modo mando")
        m.syncRaDec(obj.position(clock(), site))
        onEvent("Sync en ${obj.displayName}")
    }

    /** Rough alignment from the phone sensors: no star needed, a few degrees of error. */
    suspend fun addSensorAlignment(): AlignmentSolution {
        val m = requireMount()
        if (mode != PointingMode.STARBRIDGE) throw IllegalStateException("Solo en modo StarBridge")
        val sensor = orientationSensor?.tubePointing()
            ?: throw IllegalStateException("Sin sensor de orientación: sujeta el Android al tubo")
        val raw = readEncoders(m)
        val point = AlignmentPoint(
            "Sensores", encoderToTube(AltAz(raw.azDeg, NexStarAlt.signed(raw.altDeg))), sensor,
            SENSOR_SIGMA_AZ, SENSOR_SIGMA_ALT, fromSensor = true,
        )
        points = points.filter { !it.fromSensor } + point
        return solve()
    }

    fun removeAlignmentPoint(label: String) {
        points = points.filter { it.label != label }
        if (points.isEmpty()) solution = null else solve()
    }

    fun clearAlignment() {
        generation.incrementAndGet()
        points = emptyList()
        solution = null
        tracking = false
        trackingTarget = null
    }

    /** The tilt of the base measured with the phone ([measureLevel]): one star is then enough. */
    @Volatile var level: LevelResult? = null
        private set

    fun setLevel(result: LevelResult?) {
        level = result
        if (points.isNotEmpty()) solve()
    }

    private fun solve(): AlignmentSolution {
        // Once there are 2+ stars the sensor point only adds noise.
        val use = if (points.count { !it.fromSensor } >= 2) points.filter { !it.fromSensor } else points
        val sol = requireNotNull(AlignmentSolver.solve(use, level = level))
        solution = sol
        return sol
    }

    /**
     * Measures the tilt of the azimuth axis with a phone lying on the tube ([LevelFit]): turns
     * only the azimuth to four positions around the current one, lets it settle, averages the
     * phone's gravity at each ([gravity]), and comes back. Tracking and GoTo stop first.
     */
    suspend fun measureLevel(
        gravity: suspend () -> DoubleArray?,
        progress: suspend (phase: String, step: Int, message: String) -> Unit,
    ): LevelResult {
        val m = requireMount()
        if (mode != PointingMode.STARBRIDGE) throw IllegalStateException("La nivelación es para el modo StarBridge")
        if (gotoActive) cancelGotoJob()
        stopTracking(sendStop = true)
        val start = readEncoders(m)
        val home = AltAz(Astro.norm360(start.azDeg), NexStarAlt.signed(start.altDeg))
        val azSign = solution?.params?.azSign ?: 1
        val samples = mutableListOf<LevelSample>()
        for ((i, d) in LEVEL_OFFSETS.withIndex()) {
            progress("moving", i + 1, "Girando a la posición ${i + 1} de ${LEVEL_OFFSETS.size}…")
            slewTo(m, AltAz(Astro.norm360(home.azDeg + d), home.altDeg), finalLeg = true)
            progress("measuring", i + 1, "Midiendo la inclinación (${i + 1} de ${LEVEL_OFFSETS.size})…")
            delay(LEVEL_SETTLE_MS)
            val g = gravity() ?: throw IllegalStateException("El iPhone no envía su inclinación: ¿sigue abierto StarBridge EAA?")
            if (kotlin.math.abs(LevelFit.angles(g[0], g[1], g[2]).second) > LevelFit.MAX_PHONE_TILT_DEG) {
                throw IllegalStateException("El iPhone está muy ladeado: ponlo plano sobre el tubo, con la pantalla hacia arriba")
            }
            // The tube's azimuth (the slack does not matter here: the phone measures the tube).
            samples += LevelSample(azSign * encoderToTube(readEncoders(m)).azDeg, g[0], g[1], g[2])
        }
        progress("moving", LEVEL_OFFSETS.size, "Volviendo a la posición inicial…")
        slewTo(m, home, finalLeg = true)
        val result = LevelFit.fit(samples, azSign, clock()) ?: throw IllegalStateException("No se pudo calcular la inclinación")
        if (result.rmsDeg > LEVEL_MAX_RMS_DEG) {
            throw IllegalStateException("Las lecturas no cuadran (${"%.2f".format(result.rmsDeg)}°): ¿se movió el iPhone o el trípode? Repite la medida")
        }
        setLevel(result)
        return result
    }

    /** Guidance towards [obj] using the phone sensors: (Δaz, Δalt) in degrees, or null. */
    fun sensorGuidance(obj: CatalogObject): Pair<Double, Double>? {
        val s = orientationSensor?.tubePointing() ?: return null
        val sky = skyOf(obj)
        return PointingParams.angleDiff(sky.azDeg, s.azDeg) to (sky.altDeg - s.altDeg)
    }

    companion object {
        val DEFAULT_SITE = Site(40.4168, -3.7038)
        const val MIN_GOTO_ALT = 0.0
        /** No GoTo this close to the Sun while it is up. */
        const val SUN_GUARD_DEG = 15.0
        const val STAR_SIGMA = 0.05
        /** Two alignment stars closer than this cannot tell the tilt of the base. */
        const val CLOSE_STARS_DEG = 30.0
        /** Leveling: azimuths around the current one (a span of 270°, no full turn for the cables). */
        val LEVEL_OFFSETS = listOf(-135.0, -45.0, 45.0, 135.0)
        const val LEVEL_SETTLE_MS = 2_500L
        /** Readings this far from the fitted tilt mean the phone or the tripod moved. */
        const val LEVEL_MAX_RMS_DEG = 0.25
        const val SENSOR_SIGMA_AZ = 8.0
        const val SENSOR_SIGMA_ALT = 1.5
        const val GOTO_TIMEOUT_MS = 180_000L
        const val MAX_TRACK_ERROR_DEG = 3.0
        const val MAX_TRACK_RATE = 900.0 // arcsec/s = 0.25°/s: enough to take up slack quickly
        /** Feed-forward speed from the sky positions this long before and after now. */
        const val TRACK_FF_HALF_MS = 30_000L
        /** What is left of the error is corrected over this many seconds (no overshoot). */
        const val TRACK_TAU_S = 4.0
        /** The tube must move the other way at least this fast (≈0.4″/s) to change gear side. */
        const val TRACK_FLIP_DEG_S = 0.0001
        /** Speed changes below max(0.25″/s, 1 %) are not worth a new command. */
        const val RATE_DEADBAND_ARCSEC = 0.25
        const val RATE_DEADBAND_FRACTION = 0.01
        const val RATE_REFRESH_MS = 15_000L
        const val MAX_TRACK_FAILURES = 3
        const val MANUAL_QUIET_MS = 1_500L
        const val MIN_LEARN_DEG = 0.05
        /** Smaller encoder changes are not a movement (the 24-bit encoders read ~0.00002°). */
        const val MOVE_NOISE_DEG = 0.001

        /** The hand control must show a GoTo in progress (or motion) within this time. */
        const val HC_GOTO_VERIFY_MS = 3_000L
        const val VERIFY_POLL_MS = 300L
        /** Below this distance a GoTo proves nothing about the hand control. */
        const val MIN_VERIFY_DEG = 0.2
        const val MIN_MOVE_DEG = 0.02
        /** A hand-control GoTo that ends farther than this from the target is finished in software. */
        const val MAX_HC_ARRIVAL_DEG = 0.5
        const val SOFT_POLL_MS = 200L
        const val SOFT_TOLERANCE_DEG = 0.02
        /** Polls between progress checks of the software GoTo (~1.5 s). */
        const val SOFT_CHECK_POLLS = 6
        const val SOFT_WRONG_WAY_DEG = 0.1
        const val SOFT_MAX_POLLS = 900 // 3 minutes at 5 polls/s
        /** A sign flip of the error only means "arrived" this close to the target. */
        const val OVERSHOOT_MAX_DEG = 2.0
        const val WRAP_KEEP_DEG = 170.0
        const val DETACH_WAIT_MS = 1_000L
        /** Less play than this left to take up is not worth a fast move. */
        const val MIN_TAKE_UP_DEG = 0.03
        /** Rate 7 (~1°/s) until the motor direction is confirmed: a wrong guess costs ~1.5°. */
        const val UNCONFIRMED_MAX_RATE = 7
    }
}

/** Altitude from the hand control is 0..360; above 180 means negative. */
internal object NexStarAlt {
    fun signed(deg: Double) = if (deg > 180.0) deg - 360.0 else deg
}

/** True if [o] is a deep-sky object worth showing on the sky map. */
fun isMapObject(o: CatalogObject) =
    o.messier || o.body != null || (o.category != Category.STAR && (o.magnitude ?: 99.0) <= 6.0)
