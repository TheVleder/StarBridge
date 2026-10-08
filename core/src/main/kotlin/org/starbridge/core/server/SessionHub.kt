package org.starbridge.core.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.starbridge.core.astro.Astro
import org.starbridge.core.astro.Site
import org.starbridge.core.backlash.AxisMotionState
import org.starbridge.core.backlash.BacklashCalibrator
import org.starbridge.core.backlash.CenteringSlack
import org.starbridge.core.backlash.MotionSensor
import org.starbridge.core.backlash.SlackDetector
import org.starbridge.core.backlash.SlackMeter
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.catalog.CatalogObject
import org.starbridge.core.catalog.Category
import org.starbridge.core.control.GotoMethod
import org.starbridge.core.control.MotionController
import org.starbridge.core.control.TrackRate
import org.starbridge.core.catalog.Constellations
import org.starbridge.core.astro.Night
import org.starbridge.core.control.OrientationSensor
import org.starbridge.core.control.PointingMode
import org.starbridge.core.control.SiteSource
import org.starbridge.core.control.TelescopeBrain
import org.starbridge.core.control.isMapObject
import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.BacklashValues
import org.starbridge.core.mount.Direction
import org.starbridge.core.mount.MountDriver
import org.starbridge.core.mount.MountInfo
import org.starbridge.core.mount.RaDec
import org.starbridge.core.imaging.HttpFile
import org.starbridge.core.imaging.ImagingSession
import org.starbridge.core.imaging.MountLink
import org.starbridge.core.pointing.AlignmentSolution
import org.starbridge.core.pointing.LevelFit
import org.starbridge.core.pointing.LevelResult
import org.starbridge.core.sky.Zone
import org.starbridge.core.sky.Zones
import java.util.concurrent.ConcurrentHashMap

/** Persistent key/value settings (SharedPreferences on Android). */
interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class MemorySettings : SettingsStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
}

/**
 * Connects browser clients (WebSocket, JSON text frames) to the telescope.
 * Transport-agnostic: the Android server only forwards frames here.
 *
 * Client → server {"type": ...}:
 *  takeControl · slew{axis,dir,rate} · hold{axis} · stop{axis} · stopAll
 *  goto{id} · cancelGoto · track{on} · search{q,category?} · sky · object{id}
 *  alignState · alignAdd{id} · alignSensor · alignRemove{label} · alignClear · sync{id} · guide{id}
 *  setMode{mode} · setSite{lat,lon} · setSetting{key,value}
 *  goto{id, rough?} (rough: if unaligned, first a quick alignment with the phone sensors)
 *  getBacklash · setBacklash{axis,pos,neg} · calibrateBacklash{axis}
 *  slackVisualStart{axis} · slackVisualMark · slackMeter{on} · slackMeterClear{axis?} · slackMeterSave{axis}
 *  diag
 *  Camera (if the Android has one, see [ImagingSession]): imgState · imgCameras · imgSelect{id} ·
 *  imgSettings{settings} · imgStart · imgPause · imgResume · imgStop · imgReset · imgPreview ·
 *  imgFocus{on} · imgLensFocus · imgExposure · imgOptimize · imgDarks{count} · imgDeleteDarks ·
 *  imgSave · imgRecover{id} · imgRecords
 * Visible zones: zones · setZone{zone} · deleteZone{id} → zones (to everyone); objects get inZone.
 * alignAdd{id, checkFinish?, force?} → alignFinish{id, azm?, alt?} when centred against the GoTo arrival.
 * Leveling with the iPhone: levelStart · levelCancel · levelClear → level{phase, step, of, message, result?};
 *  the hub asks the iPhone for gravity (sensorCmd) and it streams gravity{x, y, z}.
 * Server → client: info · status · searchResult · sky · object · alignment · guide · backlash ·
 *  calibration · visualCal · slackMeter · diag · event · error · imaging · imgCameras · imgRecords · imgSaved
 * HTTP (with ?k=KEY): /img/view.jpg · stack.jpg · single.jpg · thumb.jpg · stars.jpg · /img/file/NAME · /diag.txt
 */
class SessionHub(
    private val scope: CoroutineScope,
    private val catalog: Catalog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val statusIntervalMs: Long = 1000,
    /** Monotonic clock for the movement watchdog (wall clock may jump). */
    private val monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
    private val settings: SettingsStore = MemorySettings(),
    trackingPeriodMs: Long = 1000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val clients = ConcurrentHashMap<String, suspend (String) -> Unit>()
    private val closers = ConcurrentHashMap<String, suspend () -> Unit>()

    // --- Access key -------------------------------------------------------------------
    // Only devices that scanned the QR (URL with ?k=KEY) may control the telescope.

    @Volatile var accessKey: String = settings.get(KEY_ACCESS) ?: newKey().also { settings.put(KEY_ACCESS, it) }
        private set

    /** The parts of the sky the user can really see (window, trees…): shared by every page. */
    @Volatile var zones: Zones = Zones.parse(settings.get(KEY_ZONES))
        private set

    /** Constant-time comparison: the key must not leak through timing. */
    fun checkKey(candidate: String?): Boolean {
        if (candidate == null) return false
        return java.security.MessageDigest.isEqual(normalizeKey(candidate).toByteArray(), accessKey.toByteArray())
    }

    /** The code as people read it: groups of four, e.g. "j576-xmkf-mvu2". */
    val displayKey: String get() = accessKey.chunked(4).joinToString("-")

    /** New key: every connected device is disconnected and must scan the new QR. */
    suspend fun rotateKey(): String {
        accessKey = newKey()
        settings.put(KEY_ACCESS, accessKey)
        closers.values.forEach { runCatching { it() } }
        return accessKey
    }

    /** Link to put in the QR, e.g. http://172.20.10.3:8080/?k=… */
    fun shareUrl(baseUrl: String) = "${baseUrl.trimEnd('/')}/?k=$accessKey"

    private val lock = Mutex()

    @Volatile private var mount: MountDriver? = null
    @Volatile private var motion: MotionController? = null
    @Volatile private var calibrationJob: Job? = null
    @Volatile private var statusJob: Job? = null

    val brain = TelescopeBrain(
        scope, catalog, clock, onEvent = ::event,
        isManualActive = { motion?.let { mc -> Axis.entries.any { mc.isMoving(it) } } ?: false },
        trackingPeriodMs = trackingPeriodMs,
    ).also { b ->
        settings.get(KEY_APPROACH)?.toDoubleOrNull()?.let { b.approachDeg = it }
        GotoMethod.of(settings.get(KEY_GOTO_METHOD))?.let { b.gotoMethod = it }
        settings.get(KEY_MANUAL_TAKE_UP)?.toBooleanStrictOrNull()?.let { b.manualTakeUp = it }
        settings.get(KEY_SLACK_AZ)?.toDoubleOrNull()?.let { runCatching { b.setSlack(Axis.AZM, it) } }
        settings.get(KEY_SLACK_ALT)?.toDoubleOrNull()?.let { runCatching { b.setSlack(Axis.ALT, it) } }
        val lat = settings.get(KEY_SITE_LAT)?.toDoubleOrNull()
        val lon = settings.get(KEY_SITE_LON)?.toDoubleOrNull()
        if (lat != null && lon != null) b.setManualSite(Site(lat, lon))
        parseLevel(settings.get(KEY_LEVEL))?.let { b.setLevel(it) }
    }

    @Volatile var slackDetector: SlackDetector? = null
        private set

    /** Phone gyroscope: slack detection and backlash auto-calibration. */
    @Volatile var motionSensor: MotionSensor? = null
        set(value) {
            field = value
            slackDetector = value?.let { SlackDetector(it, monotonic) }
        }

    /** Hand-driven backlash measurement (arrows on one phone, gyroscope of the Android on the tube). */
    @Volatile private var slackMeter: SlackMeter? = null
    @Volatile private var slackMeterOwner: String? = null
    @Volatile private var slackMeterJob: Job? = null

    /** Bumped by every stop of an axis: tells a slew that waited for the encoder it was released. */
    private val stopSeq = Axis.entries.associateWith { java.util.concurrent.atomic.AtomicLong() }

    var orientationSensor: OrientationSensor?
        get() = brain.orientationSensor
        set(value) { brain.orientationSensor = value }

    // --- Camera / live stacking (optional) ----------------------------------------------

    // --- Things only one platform has (the PC program's COM port choice…) -----------------

    /** Messages "pc…" go to it; its state is sent to every page that connects. */
    interface PlatformControls {
        fun state(): JsonObject?
        suspend fun handle(type: String, msg: JsonObject): JsonObject?
    }

    @Volatile var platform: PlatformControls? = null

    // --- Other StarBridge brains on the WiFi (Beacon) -------------------------------------

    /** What this brain is: "pc" or "android" (set by the platform). */
    @Volatile var brainKind: String = "android"

    /** The telescope is connected to this brain. */
    val hasTelescope: Boolean get() = mount != null

    @Volatile var peers: List<org.starbridge.core.net.Peer> = emptyList()
        private set

    /** Brains seen on the network changed: every page learns where the telescope is. */
    fun setPeers(list: List<org.starbridge.core.net.Peer>) {
        peers = list
        scope.launch { broadcast(peersMessage()) }
    }

    private fun peersMessage() = buildJsonObject {
        put("type", "peers")
        put("selfKind", brainKind)
        put("selfTelescope", mount != null)
        putJsonArray("items") {
            peers.forEach { p ->
                add(buildJsonObject {
                    put("id", p.info.id); put("kind", p.info.kind); put("name", p.info.name)
                    put("url", p.baseUrl); put("telescope", p.info.telescope)
                    p.info.model?.let { put("model", it) }
                })
            }
        }
    }

    /** Sends imaging updates to every client. Give it to the [ImagingSession]. */
    val broadcaster: suspend (JsonObject) -> Unit = { broadcast(it) }

    @Volatile private var lastSky: Pair<Double, Double>? = null

    /** What the camera side needs to know about the mount. */
    val mountLink: MountLink = object : MountLink {
        override fun connected() = mount != null
        override fun moving(): Boolean {
            val mc = motion ?: return false
            return brain.gotoActive || mc.anyMoving || calibrationJob?.isActive == true
        }
        override fun gotoActive() = mount != null && brain.gotoActive
        override fun pointing() = lastSky
        override fun latitude() = brain.site.latitudeDeg
        override fun target() = brain.trackingLabel?.takeIf { it != "posición actual" }
    }

    @Volatile var imaging: ImagingSession? = null
        set(value) {
            field = value
            value?.mount = mountLink
        }

    private val imagingQueue = kotlinx.coroutines.channels.Channel<Triple<String, String, JsonObject>>(64)
    @Volatile private var imagingWorker: Job? = null

    @Synchronized
    private fun ensureImagingWorker() {
        if (imagingWorker?.isActive == true) return
        imagingWorker = scope.launch {
            for ((clientId, type, msg) in imagingQueue) {
                try {
                    val im = imaging ?: throw IllegalStateException("La cámara no está disponible en este Android")
                    im.handle(type, msg)?.let { reply(clientId, it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reply(clientId, error(e.message ?: e.toString()))
                }
            }
        }
    }

    /** Pictures and exports of the imaging session; null without a valid key. */
    fun imagingHttp(path: String, key: String?): HttpFile? {
        if (!checkKey(key)) return null
        return imaging?.http(path)
    }

    // --- Mount lifecycle ------------------------------------------------------------

    suspend fun attachMount(driver: MountDriver, mountInfo: MountInfo) {
        if (slackMeter != null) {
            stopSlackMeter()
            slackMeter = null
        }
        logLine("Telescopio conectado: mando v${mountInfo.handControlVersion}")
        lock.withLock {
            motion?.close()
            mount = driver
            motion = MotionController(driver, scope, now = monotonic, onEvent = ::event, onAxisStopped = { slackMeter?.onRelease(it) })
        }
        brain.attach(driver, mountInfo)
        broadcast(infoMessage())
        broadcast(peersMessage())
        broadcast(alignmentMessage())
        ensureStatusLoop()
    }

    suspend fun detachMount(reason: String) {
        calibrationJob?.cancel()
        centering = null
        stopSlackMeter()
        slackMeter = null // it belongs to that mount
        brain.detach()
        lock.withLock {
            motion?.close()
            mount = null
            motion = null
        }
        event("Telescopio desconectado: $reason")
        broadcast(infoMessage())
        broadcast(peersMessage())
    }

    /**
     * The app is closing: like STOP, but from the Android itself. Nothing (GoTo, tracking,
     * measurements) may move the motors afterwards.
     */
    suspend fun haltAll() {
        calibrationJob?.cancel()
        stopSlackMeter()
        brain.emergencyHalt()
        motion?.stopAll("StarBridge se está cerrando: telescopio parado") ?: mount?.emergencyStop()
    }

    suspend fun updateGps(site: Site, accuracyM: Double?, precise: Boolean = true) {
        val first = brain.siteSource != SiteSource.GPS
        brain.updateGps(site, accuracyM, precise)
        if (first) {
            broadcast(infoMessage())
            broadcast(alignmentMessage())
        }
    }

    // --- Clients ---------------------------------------------------------------------

    /** [close] disconnects the client (used when the access key changes). */
    suspend fun onConnect(clientId: String, close: suspend () -> Unit = {}, send: suspend (String) -> Unit) {
        clients[clientId] = send
        closers[clientId] = close
        send(infoMessage(clientId).toString())
        send(peersMessage().toString())
        platform?.state()?.let { send(it.toString()) }
        send(alignmentMessage().toString())
        send(zonesMessage().toString())
        imaging?.let { send(it.state().toString()) }
        cams.values.forEach { send(it.status.toString()) }
        ensureStatusLoop()
    }

    suspend fun onDisconnect(clientId: String) {
        clients.remove(clientId)
        closers.remove(clientId)
        // The device that started a visual calibration is the one watching the star.
        if (visualMark != null && visualOwner == clientId) calibrationJob?.cancel()
        if (slackMeterOwner == clientId) stopSlackMeter()
        motion?.clientGone(clientId)
        // a remote camera that leaves is offline for every page
        cams.entries.filter { it.value.clientId == clientId }.forEach { (source, _) ->
            cams.remove(source)
            broadcast(buildJsonObject { put("type", "camStatus"); put("source", source); put("online", false) })
        }
    }

    /** STOP messages are handled at once, without waiting behind the client's other messages. */
    fun isUrgent(text: String): Boolean =
        runCatching { json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
            .let { it == "stopAll" || it == "stop" }

    suspend fun onMessage(clientId: String, text: String) {
        val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            reply(clientId, error("JSON no válido")); return
        }
        val type = msg["type"]?.jsonPrimitive?.content
        try {
            when (type) {
                "stopAll" -> {
                    // Order matters: nothing may restart the motors after a STOP.
                    calibrationJob?.cancel()
                    brain.emergencyHalt()
                    Axis.entries.forEach { stopSeq.getValue(it).incrementAndGet() }
                    requireMotion().stopAll("Telescopio parado (STOP)")
                }
                "takeControl" -> requireMotion().takeControl(clientId)
                "slew" -> {
                    val axis = axisOf(msg)
                    val dir = if (msg.str("dir") == "neg") Direction.NEGATIVE else Direction.POSITIVE
                    val mc = requireMotion()
                    val meter = slackMeter?.takeIf { it.active }
                    if (meter != null) {
                        // Another phone's press must not disturb the measurement in progress.
                        mc.controllerId?.let { if (it != clientId) throw IllegalStateException("Otro dispositivo está moviendo el telescopio") }
                        // The encoder is read before the motor starts; a quick tap may be
                        // released (stop is urgent) during that read: then never start it.
                        val seq = stopSeq.getValue(axis).get()
                        meter.onPress(axis, if (dir == Direction.POSITIVE) 1 else -1)
                        if (stopSeq.getValue(axis).get() != seq) {
                            meter.onRelease(axis)
                            return
                        }
                    }
                    brain.onManualSlew(axis, dir)
                    // On a reversal, cross the known slack fast first (never while measuring it).
                    val takeUp = if (meter == null && centering == null) brain.takeUpFor(axis, dir) else null
                    slackDetector?.onSlewCommanded(axis)
                    // The computer's slider sends any speed in arcsec/s; phones send rates 1-9. The top
                    // of the slider is the hand control's fixed rate 9: variable rates may not reach the
                    // motors' real top speed (to be measured on the SLT).
                    val asked = msg["arcsec"]?.jsonPrimitive?.doubleOrNull?.coerceIn(MIN_SLEW_ARCSEC, MAX_SLEW_ARCSEC)
                    val arcsec = asked?.takeIf { it < FIXED_TOP_FROM_ARCSEC }
                    val rate = if (asked != null && arcsec == null) 9 else msg["rate"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 9) ?: 6
                    mc.startSlew(clientId, axis, dir, rate, takeUp, arcsec)
                }
                "hold" -> requireMotion().hold(clientId, axisOf(msg))
                "stop" -> {
                    calibrationJob?.cancel()
                    val axis = axisOf(msg)
                    stopSeq.getValue(axis).incrementAndGet()
                    requireMotion().stopAxis(axis)
                    slackDetector?.onStopCommanded(axis)
                    brain.onManualStop(axis)
                }
                "goto" -> {
                    val obj = requireObject(msg)
                    // "Take me there" without an alignment: a rough one from the phone sensors first.
                    if (msg["rough"]?.jsonPrimitive?.booleanOrNull == true && brain.solution == null &&
                        brain.mode == PointingMode.STARBRIDGE && brain.orientationSensor != null
                    ) {
                        brain.addSensorAlignment()
                        event("Alineación aproximada con los sensores (±5°): centra el objeto y añádelo para afinar")
                        broadcast(alignmentMessage())
                    }
                    brain.goto(obj)
                    warnOutsideZones(obj.id, brain.skyOf(obj))
                }
                "cancelGoto" -> brain.cancelGoto()
                "gotoCoords" -> {
                    val ra = msg.double("ra")
                    val dec = msg.double("dec")
                    val label = msg["label"]?.jsonPrimitive?.contentOrNull
                    brain.gotoCoordinates(ra, dec, label)
                    val now = clock()
                    warnOutsideZones(label ?: "Ese punto", Astro.apparentAltAz(Astro.precessFromJ2000(RaDec(ra, dec), now), brain.site, now))
                }
                "gotoAltAz" -> {
                    val target = org.starbridge.core.mount.AltAz(msg.double("az"), msg.double("alt"))
                    brain.gotoHorizontal(target)
                    warnOutsideZones("Ese punto", target)
                }
                "zones" -> reply(clientId, zonesMessage())
                "setZone" -> {
                    val o = msg["zone"]?.jsonObject ?: throw IllegalArgumentException("Falta la zona")
                    val id = o["id"]?.jsonPrimitive?.contentOrNull?.takeIf { want -> zones.list.any { it.id == want } } ?: zones.newId()
                    updateZones(zones.upsert(Zone.fromJson(o, id)))
                }
                "deleteZone" -> updateZones(zones.delete(msg.str("id")))
                "park" -> brain.park()
                "track" -> if (msg["on"]?.jsonPrimitive?.booleanOrNull == true) {
                    val rate = TrackRate.of(msg["rate"]?.jsonPrimitive?.contentOrNull) ?: TrackRate.SIDEREAL
                    brain.startTracking(label = "posición actual", rate = rate)
                } else {
                    brain.stopTracking(sendStop = true)
                }
                "objectNight" -> reply(clientId, nightMessage(requireObject(msg)))
                "trace" -> reply(clientId, buildJsonObject {
                    put("type", "trace")
                    putJsonArray("lines") { (mount?.trace() ?: emptyList()).forEach { add(JsonPrimitive(it)) } }
                })
                "raw" -> reply(clientId, rawCommand(msg))
                "photos" -> reply(clientId, photosMessage())
                // A remote camera (the iPhone app) reports its state; every page sees it.
                "camStatus" -> {
                    val source = camSource(msg)
                    val status = buildJsonObject {
                        msg.forEach { (k, v) -> if (k != "online") put(k, v) }
                        put("source", source)
                        put("online", true)
                    }
                    cams[source] = RemoteCam(clientId, status)
                    broadcast(status)
                }
                // A page drives that camera: the order goes, unchanged, to the camera's connection.
                "camCmd" -> {
                    val cam = cams[camSource(msg)] ?: throw IllegalStateException("La cámara del iPhone no está conectada")
                    reply(cam.clientId, msg)
                }
                // The iPhone's accelerometer while leveling ([startLevel]).
                "gravity" -> if (camSource(msg) == LEVEL_SOURCE) {
                    val g = doubleArrayOf(msg.double("x"), msg.double("y"), msg.double("z"))
                    synchronized(gravitySamples) {
                        gravitySamples.addLast(monotonic() to g)
                        while (gravitySamples.size > 400) gravitySamples.removeFirst()
                    }
                }
                "levelStart" -> startLevel()
                "levelCancel" -> if (levelRunning) calibrationJob?.cancel()
                "levelClear" -> {
                    brain.setLevel(null)
                    settings.put(KEY_LEVEL, "")
                    broadcast(alignmentMessage())
                }
                "camNotice" -> broadcast(buildJsonObject {
                    msg.forEach { (k, v) -> put(k, v) }
                    put("source", camSource(msg))
                })
                "search" -> reply(
                    clientId,
                    searchResult(msg["q"]?.jsonPrimitive?.content ?: "", msg["category"]?.jsonPrimitive?.content),
                )
                "sky" -> reply(
                    clientId,
                    skyMessage(
                        withLines = msg["lines"]?.jsonPrimitive?.booleanOrNull == true,
                        deep = msg["deep"]?.jsonPrimitive?.booleanOrNull == true,
                    ),
                )
                "object" -> reply(clientId, objectMessage(requireObject(msg)))
                "alignState" -> reply(clientId, alignmentMessage())
                "alignAdd" -> {
                    val obj = requireObject(msg)
                    // Pages that ask for it: centred against the GoTo direction, the point would carry
                    // any error of the slack value. Say which arrows to finish with (force: add anyway).
                    if (msg["checkFinish"]?.jsonPrimitive?.booleanOrNull == true && msg["force"]?.jsonPrimitive?.booleanOrNull != true) {
                        val wrong = brain.finishAgainstArrival(obj)
                        if (wrong.isNotEmpty()) {
                            reply(clientId, buildJsonObject {
                                put("type", "alignFinish")
                                put("id", obj.id)
                                wrong[Axis.AZM]?.let { put("azm", it) }
                                wrong[Axis.ALT]?.let { put("alt", it) }
                            })
                            return
                        }
                    }
                    val result = brain.addAlignmentStar(obj)
                    result.warning?.let { reply(clientId, error(it)) }
                    broadcast(alignmentMessage())
                }
                "alignSensor" -> {
                    brain.addSensorAlignment()
                    broadcast(alignmentMessage())
                }
                "alignRemove" -> {
                    brain.removeAlignmentPoint(msg.str("label"))
                    broadcast(alignmentMessage())
                }
                "alignClear" -> {
                    brain.stopTracking(sendStop = true)
                    brain.clearAlignment()
                    broadcast(alignmentMessage())
                }
                "sync" -> brain.syncHandControl(requireObject(msg))
                "guide" -> reply(clientId, guideMessage(requireObject(msg)))
                "setMode" -> {
                    brain.setMode(if (msg.str("mode") == "hc") PointingMode.HAND_CONTROL else PointingMode.STARBRIDGE)
                    broadcast(infoMessage())
                    broadcast(alignmentMessage())
                }
                "setSite" -> {
                    val lat = msg.double("lat")
                    val lon = msg.double("lon")
                    require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Lugar no válido" }
                    settings.put(KEY_SITE_LAT, lat.toString())
                    settings.put(KEY_SITE_LON, lon.toString())
                    brain.setManualSite(Site(lat, lon))
                    broadcast(infoMessage())
                }
                "setSetting" -> {
                    val key = msg.str("key")
                    val value = msg.str("value")
                    when (key) {
                        KEY_APPROACH -> {
                            val v = value.toDouble()
                            require(v in 0.0..3.0) { "La aproximación debe estar entre 0 y 3°" }
                            brain.approachDeg = v
                        }
                        KEY_MOUNTING -> require(value in setOf("top", "camera")) { "Montaje no válido" }
                        KEY_SLACK_AZ -> brain.setSlack(Axis.AZM, value.toDouble())
                        KEY_SLACK_ALT -> brain.setSlack(Axis.ALT, value.toDouble())
                        KEY_GOTO_METHOD -> brain.gotoMethod = GotoMethod.of(value) ?: throw IllegalArgumentException("Método de GoTo no válido")
                        KEY_MANUAL_TAKE_UP -> brain.manualTakeUp = value.toBooleanStrict()
                        else -> throw IllegalArgumentException("Ajuste desconocido: $key")
                    }
                    settings.put(key, value)
                    broadcast(infoMessage())
                }
                "getBacklash" -> reply(clientId, backlashMessage())
                "setBacklash" -> {
                    requireMount().setBacklash(axisOf(msg), BacklashValues(msg.int("pos"), msg.int("neg")))
                    reply(clientId, backlashMessage())
                }
                "calibrateBacklash" -> startCalibration(clientId, axisOf(msg))
                "slackVisualStart" -> startVisualSlack(clientId, axisOf(msg))
                "slackVisualMark" -> if (visualOwner == clientId) visualMark?.complete(Unit)
                "slackCenterStart" -> {
                    val obj = requireObject(msg)
                    // The user's last touch must be the last movement: nothing else may move the motors.
                    calibrationJob?.cancel()
                    brain.cancelGoto()
                    brain.stopTracking(sendStop = true)
                    centeringNative = runCatching { backlashMessage() }.getOrNull()
                    centering = CenteringSlack(obj.id)
                    reply(clientId, centeringMessage())
                }
                "slackCenterMark" -> {
                    val c = centering ?: throw IllegalStateException("Empieza la medida primero")
                    val obj = catalog.find(c.starId) ?: throw IllegalStateException("Estrella desconocida")
                    c.add(brain.centeringMark(obj))
                    reply(clientId, centeringMessage())
                }
                "slackCenterSave" -> {
                    val c = centering ?: throw IllegalStateException("No hay ninguna medida")
                    val saved = Axis.entries.mapNotNull { axis ->
                        val s = c.estimate(axis).slackDeg ?: return@mapNotNull null
                        val slack = r(s).coerceIn(0.0, 3.0)
                        brain.setSlack(axis, slack)
                        settings.put(if (axis == Axis.AZM) KEY_SLACK_AZ else KEY_SLACK_ALT, slack.toString())
                        "${axisName(axis)} ${"%.2f".format(java.util.Locale.ROOT, slack)}°"
                    }
                    if (saved.isEmpty()) throw IllegalStateException("Aún no hay ninguna medida completa")
                    event("Holgura guardada: ${saved.joinToString(" · ")}")
                    broadcast(infoMessage())
                    reply(clientId, centeringMessage())
                }
                "slackCenterStop" -> {
                    centering = null
                    reply(clientId, centeringMessage())
                }
                "slackMeter" -> if (msg["on"]?.jsonPrimitive?.booleanOrNull == true) startSlackMeter(clientId) else stopSlackMeter()
                "slackMeterClear" -> {
                    slackMeter?.clear(msg["axis"]?.let { axisOf(msg) })
                    broadcast(slackMeterMessage())
                }
                "slackMeterSave" -> {
                    val axis = axisOf(msg)
                    val s = slackMeter?.summary(axis)?.slackDeg
                        ?: throw IllegalStateException("Aún no hay medidas con cambio de sentido en ${axisName(axis)}")
                    val slack = r(s).coerceIn(0.0, 3.0)
                    brain.setSlack(axis, slack)
                    settings.put(if (axis == Axis.AZM) KEY_SLACK_AZ else KEY_SLACK_ALT, slack.toString())
                    event("Holgura de ${axisName(axis)} guardada: ${"%.2f".format(slack)}°")
                    broadcast(infoMessage())
                    broadcast(slackMeterMessage())
                }
                "diag" -> reply(clientId, buildJsonObject { put("type", "diag"); put("text", diagnosticsText()) })
                else -> if (type != null && type.startsWith("pc") && platform != null) {
                    platform?.handle(type, msg)?.let { reply(clientId, it) }
                } else if (type != null && type.startsWith("img")) {
                    if (imaging == null) throw IllegalStateException("La cámara no está disponible en este Android")
                    // Camera commands can take seconds (saving, waiting for a frame): they run
                    // in order on their own queue so this client's slew/hold/stop never wait.
                    ensureImagingWorker()
                    if (!imagingQueue.trySend(Triple(clientId, type, msg)).isSuccess) {
                        throw IllegalStateException("Demasiadas órdenes de cámara a la vez")
                    }
                } else {
                    reply(clientId, error("Mensaje desconocido: $type"))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logLine("Error en '$type': ${e.message ?: e}")
            reply(clientId, error(e.message ?: e.toString()))
        }
    }

    // --- Helpers ------------------------------------------------------------------------

    private fun requireMount() = mount ?: throw IllegalStateException("Telescopio no conectado")
    private fun requireMotion() = motion ?: throw IllegalStateException("Telescopio no conectado")
    private fun requireObject(m: JsonObject) =
        catalog.find(m.str("id")) ?: throw IllegalArgumentException("Objeto desconocido: ${m.str("id")}")

    private fun axisOf(m: JsonObject) = if (m.str("axis") == "alt") Axis.ALT else Axis.AZM
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Falta $k")
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.int ?: throw IllegalArgumentException("Falta $k")
    private fun JsonObject.double(k: String) = this[k]?.jsonPrimitive?.double ?: throw IllegalArgumentException("Falta $k")

    private fun error(text: String) = buildJsonObject { put("type", "error"); put("message", text) }

    // --- Visible zones ------------------------------------------------------------------

    private fun zonesMessage() = buildJsonObject {
        put("type", "zones")
        put("zones", zones.toJson())
    }

    private suspend fun updateZones(z: Zones) {
        zones = z
        settings.put(KEY_ZONES, z.toJson().toString())
        broadcast(zonesMessage())
    }

    /** A GoTo outside every zone that is on still runs (the mount can go there), but say so. */
    private fun warnOutsideZones(what: String, sky: org.starbridge.core.mount.AltAz) {
        if (sky.altDeg > 0 && zones.visible(sky) == false) event("$what está fuera de tu zona visible")
    }

    private fun r(x: Double, digits: Int = 3): Double {
        var f = 1.0
        repeat(digits) { f *= 10 }
        return Math.round(x * f) / f
    }

    private fun startCalibration(clientId: String, axis: Axis) {
        val sensor = motionSensor ?: throw IllegalStateException("Este Android no tiene giroscopio")
        val m = requireMount()
        if (calibrationJob?.isActive == true) throw IllegalStateException("Ya hay una calibración en marcha")
        calibrationJob = scope.launch {
            event("Calibrando holgura en ${axisName(axis)}… pulsa STOP para cancelar")
            try {
                val res = BacklashCalibrator(m, sensor).calibrate(axis)
                // The measured slack also feeds the pointing model.
                val slack = ((res.slackPositiveDeg + res.slackNegativeDeg) / 2).coerceIn(0.0, 3.0)
                brain.setSlack(axis, slack)
                settings.put(if (axis == Axis.AZM) KEY_SLACK_AZ else KEY_SLACK_ALT, slack.toString())
                broadcast(infoMessage())
                reply(clientId, buildJsonObject {
                    put("type", "calibration")
                    put("axis", axis.name.lowercase())
                    put("slackPosDeg", r(res.slackPositiveDeg))
                    put("slackNegDeg", r(res.slackNegativeDeg))
                    put("suggestedPos", BacklashCalibrator.toNativeValue(res.slackPositiveDeg))
                    put("suggestedNeg", BacklashCalibrator.toNativeValue(res.slackNegativeDeg))
                })
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { m.emergencyStop() }
                throw e
            } catch (e: Exception) {
                reply(clientId, error("Calibración fallida: ${e.message}"))
                runCatching { m.emergencyStop() }
            }
        }
    }

    /** Slack measured by centring a star with opposite last touches ([CenteringSlack]). */
    @Volatile private var centering: CenteringSlack? = null
    /** The hand control's own anti-backlash when that measurement started (it changes the result). */
    @Volatile private var centeringNative: JsonObject? = null

    private fun centeringMessage() = buildJsonObject {
        put("type", "slackCenter")
        val c = centering
        put("active", c != null)
        if (c == null) return@buildJsonObject
        put("star", c.starId)
        put("marks", c.count)
        for (axis in Axis.entries) {
            val e = c.estimate(axis)
            putJsonObject(axis.name.lowercase()) {
                c.last?.let { put("last", brain.commandFor(axis, CenteringSlack.dirOf(axis, it))) }
                // As an arrow ("pos"/"neg" command), so the page can draw it.
                put("next", brain.commandFor(axis, c.next(axis)))
                putJsonArray("samples") { e.samples.forEach { add(JsonPrimitive(r(it))) } }
                e.slackDeg?.let { put("slack", r(it)) }
                put("spread", r(e.spreadDeg))
                put("saved", r(brain.slack(axis)))
            }
        }
        centeringNative?.let { put("native", it) }
    }

    @Volatile private var visualMark: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile private var visualOwner: String? = null

    /**
     * Backlash without sensors: load the gears on one side, then creep the other way while the
     * user watches a star in the eyepiece and taps when it starts to move. Encoder travel until
     * then = slack. Bounded in time and travel; STOP cancels it.
     */
    private fun startVisualSlack(clientId: String, axis: Axis) {
        val m = requireMount()
        if (calibrationJob?.isActive == true) throw IllegalStateException("Ya hay una calibración en marcha")
        brain.emergencyHalt() // no tracking or GoTo while measuring
        val mark = kotlinx.coroutines.CompletableDeferred<Unit>()
        visualMark = mark
        visualOwner = clientId
        calibrationJob = scope.launch {
            fun axisAngle(p: org.starbridge.core.mount.AltAz) = if (axis == Axis.AZM) p.azDeg else p.altDeg
            fun visual(phase: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
                put("type", "visualCal"); put("axis", axis.name.lowercase()); put("phase", phase); extra()
            }
            try {
                reply(clientId, visual("preparing"))
                m.slew(axis, Direction.NEGATIVE, VISUAL_RATE)
                delay(VISUAL_PRELOAD_MS)
                m.stop(axis)
                delay(800)
                val start = axisAngle(m.getAltAz())
                m.slew(axis, Direction.POSITIVE, VISUAL_RATE)
                reply(clientId, visual("watching"))
                val t0 = monotonic()
                var signed = 0.0
                var travel = 0.0
                while (!mark.isCompleted) {
                    delay(100)
                    signed = org.starbridge.core.pointing.PointingParams.angleDiff(axisAngle(m.getAltAz()), start)
                    travel = kotlin.math.abs(signed)
                    if (travel > VISUAL_MAX_DEG || monotonic() - t0 > VISUAL_MAX_MS) {
                        throw IllegalStateException("No se marcó el movimiento: ¿se veía la estrella en el ocular?")
                    }
                }
                m.stop(axis)
                brain.noteMovement(axis, if (signed >= 0) 1 else -1)
                // Human reaction time (~0.3 s) at the creeping speed.
                val slack = (travel - VISUAL_REACTION_S * VISUAL_RATE_DEG_S).coerceIn(0.0, 3.0)
                brain.setSlack(axis, slack)
                settings.put(if (axis == Axis.AZM) KEY_SLACK_AZ else KEY_SLACK_ALT, slack.toString())
                reply(clientId, visual("done") { put("slackDeg", r(slack)) })
                broadcast(infoMessage())
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { m.stop(axis) }
                throw e
            } catch (e: Exception) {
                runCatching { m.stop(axis) }
                reply(clientId, visual("failed") { put("message", e.message ?: e.toString()) })
            } finally {
                visualMark = null
            }
        }
    }

    // --- Hand-driven backlash measurement ---------------------------------------------------

    private fun startSlackMeter(clientId: String) {
        val sensor = motionSensor ?: throw IllegalStateException("Este Android no tiene giroscopio")
        val m = requireMount()
        if (calibrationJob?.isActive == true) throw IllegalStateException("Hay una calibración en marcha")
        // The tube must stay still between presses: no tracking or GoTo while measuring.
        brain.emergencyHalt()
        scope.launch { Axis.entries.forEach { runCatching { m.stop(it) } } }
        // Reopening the mode keeps this connection's measurements.
        val meter = slackMeter ?: SlackMeter(m, sensor, scope, monotonic)
        meter.start()
        slackMeter = meter
        slackMeterOwner = clientId
        if (slackMeterJob?.isActive != true) {
            slackMeterJob = scope.launch {
                while (isActive && slackMeter?.active == true) {
                    broadcast(slackMeterMessage())
                    delay(SLACK_METER_PUSH_MS)
                }
            }
        }
    }

    private fun stopSlackMeter() {
        slackMeter?.stop()
        slackMeterOwner = null
        slackMeterJob?.cancel()
        scope.launch { broadcast(slackMeterMessage()) }
    }

    private fun slackMeterMessage() = buildJsonObject {
        put("type", "slackMeter")
        val meter = slackMeter
        put("active", meter?.active == true)
        if (meter == null) return@buildJsonObject
        val live = meter.live()
        putJsonObject("live") {
            live.axis?.let { put("axis", it.name.lowercase()) }
            put("dir", live.direction)
            put("phase", live.phase.name.lowercase())
            put("travelDeg", r(live.travelDeg))
            live.gyroDegS?.let { put("gyroDegS", r(it)) }
            live.thresholdDegS?.let { put("thresholdDegS", r(it)) }
        }
        putJsonObject("axes") {
            for (axis in Axis.entries) {
                val s = meter.summary(axis)
                putJsonObject(axis.name.lowercase()) {
                    s.slackDeg?.let { put("slackDeg", r(it)) }
                    put("lagDeg", r(s.lagDeg))
                    put("spreadDeg", r(s.spreadDeg))
                    put("reversals", s.reversals)
                    put("saved", r(brain.slack(axis)))
                    put("loaded", live.loaded[axis] ?: 0)
                    putJsonArray("items") {
                        meter.measurements.filter { it.axis == axis }.forEach { mm ->
                            add(buildJsonObject {
                                put("dir", mm.direction)
                                put("deg", r(mm.rawDeg))
                                put("kind", mm.kind.name.lowercase())
                            })
                        }
                    }
                }
            }
        }
    }

    // --- Diagnostics ------------------------------------------------------------------------

    private val eventLog = ArrayDeque<String>()

    /** Something the Android saw (screen on/off, WiFi lost…): only for the diagnostics. */
    fun note(text: String) = logLine(text)

    private fun logLine(text: String) {
        val t = java.time.LocalTime.now()
        synchronized(eventLog) {
            eventLog.addLast("%02d:%02d:%02d  %s".format(t.hour, t.minute, t.second, text))
            while (eventLog.size > EVENT_LOG_LINES) eventLog.removeFirst()
        }
    }

    /** Plain-text report: state, recent events and the serial traffic with the hand control. */
    fun diagnosticsText(): String = buildString {
        // Decimal points, whatever the phone's language: "40.4168, -3.7038" must stay unambiguous.
        fun f(x: Double, digits: Int) = String.format(java.util.Locale.ROOT, "%.${digits}f", x)
        val i = brain.info
        val sol = brain.solution
        appendLine("StarBridge · diagnóstico")
        appendLine(
            if (i == null) "Telescopio: no conectado"
            else "Mando: v${i.handControlVersion}${i.model?.let { " · modelo $it" } ?: ""} · alineado (mando): ${if (brain.hcAligned) "sí" else "no"}",
        )
        appendLine("Modo: ${modeName()} · GoTo: ${brain.gotoMethod.key} · el mando ejecuta 'b': " +
            when (brain.handControlGotoWorks) { true -> "sí"; false -> "NO"; null -> "sin probar" })
        appendLine(
            "Alineación: " + when {
                sol == null -> "ninguna"
                sol.sensorOnly -> "aproximada con los sensores"
                else -> "${sol.starCount} estrella(s) · error ${f(sol.rmsArcmin, 1)}′"
            },
        )
        appendLine("Lugar: ${f(brain.site.latitudeDeg, 4)}, ${f(brain.site.longitudeDeg, 4)} (${brain.siteSource.name.lowercase()}${brain.gpsAccuracyM?.let { " ±${it.toInt()} m" } ?: ""})")
        fun play(a: Axis) = "${f(brain.slack(a), 2)}° (motor ${brain.gearOffset(a)?.let { "${if (it >= 0) "+" else ""}${f(it, 2)}°" } ?: "sin saber dónde"})"
        appendLine("Holgura: acimut ${play(Axis.AZM)} · altitud ${play(Axis.ALT)} · recoger con flechas: ${if (brain.manualTakeUp) "sí" else "no"}")
        appendLine("Giroscopio: ${if (motionSensor != null) "sí" else "no"} · orientación: ${if (brain.orientationSensor != null) "sí" else "no"} · cámara: ${if (imaging?.available == true) "sí" else "no"}")
        appendLine()
        appendLine("--- Eventos ---")
        synchronized(eventLog) { eventLog.forEach { appendLine(it) } }
        appendLine()
        appendLine("--- Tráfico con el mando (lo último al final) ---")
        mount?.trace()?.forEach { appendLine(it) } ?: appendLine("(sin telescopio)")
    }

    private fun axisName(a: Axis) = if (a == Axis.AZM) "acimut" else "altitud"

    private fun modeName() = if (brain.mode == PointingMode.HAND_CONTROL) "hc" else "starbridge"

    // --- Messages ---------------------------------------------------------------------------

    private fun infoMessage(clientId: String? = null) = buildJsonObject {
        put("type", "info")
        clientId?.let { put("clientId", it) }
        put("connected", mount != null)
        brain.info?.let {
            put("hcVersion", it.handControlVersion.toString())
            it.model?.let { m -> put("model", m) }
            put("canSync", it.supportsSync)
        }
        put("hcAligned", brain.hcAligned)
        put("mode", modeName())
        putJsonObject("site") {
            put("lat", r(brain.site.latitudeDeg, 5))
            put("lon", r(brain.site.longitudeDeg, 5))
            put("source", brain.siteSource.name.lowercase())
            brain.gpsAccuracyM?.let { put("accuracyM", r(it, 0)) }
        }
        put("gyro", motionSensor != null)
        put("orientation", brain.orientationSensor != null)
        put("imaging", imaging?.available ?: false)
        put("brainKind", brainKind)
        putJsonObject("settings") {
            put(KEY_APPROACH, brain.approachDeg)
            put(KEY_MOUNTING, settings.get(KEY_MOUNTING) ?: "top")
            put(KEY_SLACK_AZ, r(brain.slack(Axis.AZM)))
            put(KEY_SLACK_ALT, r(brain.slack(Axis.ALT)))
            put(KEY_GOTO_METHOD, brain.gotoMethod.key)
            put(KEY_MANUAL_TAKE_UP, brain.manualTakeUp)
        }
        brain.handControlGotoWorks?.let { put("hcGoto", it) }
    }

    private fun JsonObjectBuilder.putObject(o: CatalogObject, now: Long) {
        val sky = brain.skyOf(o, now)
        val p = o.position(now, brain.site)
        put("id", o.id)
        put("name", o.name)
        put("kind", o.type)
        put("cat", o.category.name.lowercase())
        o.magnitude?.let { put("mag", it) }
        put("const", o.constellation)
        if (o.aliases.isNotEmpty()) put("aka", o.aliases.joinToString(" · "))
        put("ra", r(p.raHours, 5))
        put("dec", r(p.decDeg, 4))
        put("alt", r(sky.altDeg, 2))
        put("az", r(sky.azDeg, 2))
        put("dir", Astro.compass(sky.azDeg))
        zones.visible(sky)?.let { put("inZone", it) }
    }

    private fun searchResult(q: String, category: String?): JsonObject {
        val now = clock()
        val items: List<CatalogObject> = if (category == "best") {
            catalog.objects.asSequence()
                .filter { it.messier || it.body != null || it.alignmentStar }
                .filter { brain.skyOf(it, now).altDeg > 15 }
                .sortedBy { o -> (o.magnitude ?: -3.0) + if (o.category == Category.STAR) 3.0 else 0.0 }
                .take(40).toList()
        } else if (category == "all" || category == "ngc") {
            // everything (or every NGC/IC object) matching the text; without text, the brightest visible first
            val found = catalog.search(q, limit = Int.MAX_VALUE)
                .filter { category == "all" || it.id.startsWith("NGC") || it.id.startsWith("IC") }
                .map { it to (brain.skyOf(it, now).altDeg > 0) }
            val ordered = if (q.isBlank()) {
                found.sortedWith(compareByDescending<Pair<CatalogObject, Boolean>> { it.second }.thenBy { it.first.magnitude ?: 99.0 })
            } else {
                found.sortedByDescending { it.second } // stable: exact id matches stay first
            }
            ordered.take(120).map { it.first }
        } else if (category == "messier" || category == "solar") {
            catalog.search(q, limit = 400)
                .filter { if (category == "messier") it.messier else it.body != null }
                .sortedByDescending { brain.skyOf(it, now).altDeg > 0 }
                .take(120)
        } else {
            val cat = category?.let { c -> Category.entries.firstOrNull { it.name.equals(c, ignoreCase = true) } }
            catalog.search(q, limit = if (cat == null) 40 else 400, category = cat)
                .sortedByDescending { brain.skyOf(it, now).altDeg > 0 }
                .take(60)
        }
        return buildJsonObject {
            put("type", "searchResult")
            put("q", q)
            category?.let { put("category", it) }
            putJsonArray("items") { items.forEach { o -> add(buildJsonObject { putObject(o, now) }) } }
        }
    }

    private fun objectMessage(o: CatalogObject) = buildJsonObject {
        put("type", "object")
        putObject(o, clock())
    }

    private val constellations by lazy { runCatching { Constellations.loadDefault() }.getOrNull() }

    /**
     * Everything above the horizon for the sky map. Stars as compact arrays [az, alt, mag, label].
     * [deep]: every deep-sky object of the catalogue, not only Messier and the brightest (the
     * computer's map shows the faint ones as it zooms in).
     * [withLines]: also constellation figures as polylines of [az, alt] and labels [name, az, alt]
     * (the computer's big map; phones keep the light version).
     */
    private fun skyMessage(withLines: Boolean = false, deep: Boolean = false): JsonObject {
        val now = clock()
        return buildJsonObject {
            put("type", "sky")
            put("time", now)
            val figures = constellations
            if (withLines && figures != null) {
                fun sky(p: org.starbridge.core.mount.RaDec) = Astro.apparentAltAz(Astro.precessFromJ2000(p, now), brain.site, now)
                putJsonArray("lines") {
                    figures.lines.forEach { line ->
                        val pts = line.points.map(::sky)
                        if (pts.any { it.altDeg > -5 }) add(buildJsonArray {
                            pts.forEach { add(buildJsonArray { add(JsonPrimitive(r(it.azDeg, 2))); add(JsonPrimitive(r(it.altDeg, 2))) }) }
                        })
                    }
                }
                putJsonArray("labels") {
                    figures.labels.forEach { l ->
                        val s = sky(l.position)
                        if (s.altDeg > 0) add(buildJsonArray {
                            add(JsonPrimitive(l.name)); add(JsonPrimitive(r(s.azDeg, 2))); add(JsonPrimitive(r(s.altDeg, 2)))
                        })
                    }
                }
            }
            putJsonArray("stars") {
                catalog.objects.asSequence().filter { it.category == Category.STAR }.forEach { o ->
                    val s = brain.skyOf(o, now)
                    if (s.altDeg > -1) add(buildJsonArray {
                        add(JsonPrimitive(r(s.azDeg, 2)))
                        add(JsonPrimitive(r(s.altDeg, 2)))
                        add(JsonPrimitive(o.magnitude ?: 4.0))
                        add(JsonPrimitive(if (o.alignmentStar) o.id else ""))
                    })
                }
            }
            putJsonArray("objects") {
                catalog.objects.asSequence().filter { isMapObject(it) || (deep && it.category != Category.STAR) }.forEach { o ->
                    val s = brain.skyOf(o, now)
                    if (s.altDeg > -1) add(buildJsonObject {
                        put("id", o.id)
                        put("name", o.name)
                        put("cat", o.category.name.lowercase())
                        o.magnitude?.let { put("mag", it) }
                        put("az", r(s.azDeg, 2))
                        put("alt", r(s.altDeg, 2))
                    })
                }
            }
        }
    }

    private fun alignmentMessage(): JsonObject {
        val sol: AlignmentSolution? = brain.solution
        val now = clock()
        return buildJsonObject {
            put("type", "alignment")
            put("mode", modeName())
            put("hcAligned", brain.hcAligned)
            put("aligned", sol != null)
            put("needsCheck", brain.alignmentNeedsCheck)
            brain.level?.let { put("level", levelJson(it)) }
            sol?.let {
                put("stars", it.starCount)
                put("rmsArcmin", r(it.rmsArcmin, 1))
                put("sensorOnly", it.sensorOnly)
                put("tiltDeg", r(kotlin.math.hypot(it.params.tiltEast, it.params.tiltNorth), 2))
            }
            putJsonArray("points") {
                val starPoints = brain.points.filter { !it.fromSensor }
                val usedForFit = if (starPoints.size >= 2) starPoints else brain.points
                brain.points.forEach { p ->
                    add(buildJsonObject {
                        put("label", p.label)
                        put("sensor", p.fromSensor)
                        val idx = starPoints.indexOf(p)
                        if (idx >= 0 && usedForFit.contains(p)) {
                            sol?.residualsArcmin?.getOrNull(idx)?.let { put("residualArcmin", r(it, 1)) }
                        }
                    })
                }
            }
            putJsonArray("suggestions") {
                brain.alignmentSuggestions().forEach { s ->
                    add(buildJsonObject {
                        putObject(s.obj, now)
                        s.separationDeg?.let { put("sepDeg", r(it, 0)) }
                    })
                }
            }
        }
    }

    /** The object's coming night: altitude every 10 min with the Sun's, rise/transit/set, darkness. */
    private fun nightMessage(o: CatalogObject) = buildJsonObject {
        val n = Night.of(o, brain.site, clock())
        put("type", "objectNight")
        put("id", o.id)
        // With zones on: whether it is in one at each sample, and the time windows it is visible.
        val inZone = if (zones.active.isEmpty()) null else n.samples.map { (t, alt, _) -> alt > 0 && zones.visible(brain.skyOf(o, t)) == true }
        putJsonArray("samples") {
            n.samples.forEachIndexed { i, (t, alt, sun) ->
                add(buildJsonArray {
                    add(JsonPrimitive(t)); add(JsonPrimitive(r(alt, 1))); add(JsonPrimitive(r(sun, 1)))
                    inZone?.let { add(JsonPrimitive(if (it[i]) 1 else 0)) }
                })
            }
        }
        inZone?.let { flags ->
            putJsonArray("zoneWindows") {
                var start: Long? = null
                n.samples.forEachIndexed { i, (t, _, _) ->
                    if (flags[i] && start == null) start = t
                    val endsHere = start != null && (!flags[i] || i == flags.lastIndex)
                    if (endsHere) {
                        val end = if (flags[i]) t else n.samples[i - 1].first
                        add(buildJsonArray { add(JsonPrimitive(start)); add(JsonPrimitive(end)) })
                        start = null
                    }
                }
            }
        }
        n.rise?.let { put("rise", it) }
        n.transit?.let { put("transit", it) }
        n.set?.let { put("set", it) }
        n.darkStart?.let { put("darkStart", it) }
        n.darkEnd?.let { put("darkEnd", it) }
        n.best?.let { put("best", it) }
        put("maxAlt", r(n.maxAltDeg, 1))
    }

    /**
     * A command typed in the computer's console: bytes as numbers, answer as text up to '#' or a
     * fixed number of bytes. Anything that moves the mount must come with confirm=true.
     */
    private suspend fun rawCommand(msg: JsonObject): JsonObject {
        val m = requireMount()
        val bytes = msg["bytes"]?.jsonArray?.map { it.jsonPrimitive.int.also { b -> require(b in 0..255) { "Byte fuera de rango: $b" } }.toByte() }
            ?.toByteArray() ?: throw IllegalArgumentException("Falta bytes")
        val length = msg["response"]?.jsonPrimitive?.intOrNull
        val command = org.starbridge.core.protocol.Command(bytes, org.starbridge.core.protocol.ResponseSpec.Terminated)
        val moves = org.starbridge.core.transport.CommandQueue.isMotion(command)
        if (moves && msg["confirm"]?.jsonPrimitive?.booleanOrNull != true) {
            throw IllegalStateException("Esa orden mueve el telescopio: confírmala")
        }
        logLine("Consola: ${org.starbridge.core.transport.CommandQueue.describe(bytes, command = true)}")
        val started = monotonic()
        val answer = m.raw(bytes, length)
        return buildJsonObject {
            put("type", "raw")
            put("sent", org.starbridge.core.transport.CommandQueue.describe(bytes, command = true))
            putJsonArray("answer") { answer.forEach { add(JsonPrimitive(it.toInt() and 0xFF)) } }
            put("text", org.starbridge.core.transport.CommandQueue.describe(answer, command = false))
            put("ms", monotonic() - started)
            put("moves", moves)
        }
    }

    private fun guideMessage(o: CatalogObject) = buildJsonObject {
        put("type", "guide")
        put("id", o.id)
        brain.sensorGuidance(o)?.let { (dAz, dAlt) -> put("dAz", r(dAz, 1)); put("dAlt", r(dAlt, 1)) }
    }

    private suspend fun backlashMessage() = buildJsonObject {
        put("type", "backlash")
        val m = requireMount()
        for (axis in Axis.entries) {
            val v = m.getBacklash(axis)
            putJsonObject(axis.name.lowercase()) { put("pos", v.positive); put("neg", v.negative) }
        }
    }

    private suspend fun statusMessage(): JsonObject {
        val m = mount
        val mc = motion
        val moving = Axis.entries.associateWith { mc?.isMoving(it) ?: false }
        val pointing = if (m != null) runCatching { brain.pointing() } else null
        val goto = if (m != null && brain.mode == PointingMode.HAND_CONTROL) {
            runCatching { m.isGotoInProgress() }.getOrDefault(false)
        } else {
            brain.gotoActive
        }
        val sensor = brain.orientationSensor?.tubePointing()
        val motionStates = Axis.entries.associateWith { slackDetector?.update(it) }
        return buildJsonObject {
            put("type", "status")
            put("connected", m != null)
            put("controller", mc?.controllerId)
            put("mode", modeName())
            put("time", clock())
            put("goto", goto)
            put("tracking", brain.tracking)
            brain.trackingLabel?.let { put("trackingLabel", it) }
            if (brain.tracking) put("trackRate", brain.trackRate.key)
            put("lst", r(Astro.localSiderealDeg(clock(), brain.site) / 15.0, 5))
            brain.trackTelemetry?.takeIf { brain.tracking }?.let { t ->
                putJsonObject("track") {
                    put("at", t.at)
                    put("errAz", r(t.errAzArcsec, 1)); put("errAlt", r(t.errAltArcsec, 1))
                    put("rateAz", r(t.rateAz, 2)); put("rateAlt", r(t.rateAlt, 2))
                }
            }
            putJsonObject("gear") {
                for (axis in Axis.entries) brain.gearOffset(axis)?.let { put(axis.name.lowercase(), r(it, 3)) }
            }
            m?.linkStats()?.let { s ->
                putJsonObject("link") { put("ms", r(s.averageMs, 0)); put("commands", s.commands); put("errors", s.errors) }
            }
            pointing?.getOrNull()?.let { p ->
                putJsonObject("raw") { put("az", r(p.reported.azDeg)); put("alt", r(p.reported.altDeg)) }
                p.sky?.let {
                    lastSky = it.azDeg to it.altDeg
                    put("az", r(it.azDeg)); put("alt", r(it.altDeg)); put("dir", Astro.compass(it.azDeg))
                }
                p.raDec?.let { put("ra", r(it.raHours, 5)); put("dec", r(it.decDeg, 4)) }
            }
            pointing?.exceptionOrNull()?.let { put("error", it.message ?: it.toString()) }
            sensor?.let { putJsonObject("sensor") { put("az", r(it.azDeg, 1)); put("alt", r(it.altDeg, 1)) } }
            putJsonObject("axes") {
                for (axis in Axis.entries) {
                    putJsonObject(axis.name.lowercase()) {
                        put("moving", moving.getValue(axis))
                        put("motion", (motionStates[axis] ?: AxisMotionState.IDLE).name.lowercase())
                        slackDetector?.lastDeadTimeMs?.get(axis)?.let { put("deadTimeMs", it) }
                    }
                }
            }
        }
    }

    @Volatile private var slackJob: Job? = null

    private fun ensureStatusLoop() {
        if (statusJob?.isActive != true) {
            statusJob = scope.launch {
                while (isActive) {
                    if (clients.isNotEmpty()) runCatching { broadcast(statusMessage()) }
                    delay(statusIntervalMs)
                }
            }
        }
        // The gyroscope is sampled fast so the measured dead time is accurate (no serial I/O).
        if (slackJob?.isActive != true) {
            slackJob = scope.launch {
                while (isActive) {
                    slackDetector?.let { d -> Axis.entries.forEach { d.update(it) } }
                    delay(SLACK_SAMPLE_MS)
                }
            }
        }
    }

    private fun event(text: String) {
        logLine(text)
        scope.launch { broadcast(buildJsonObject { put("type", "event"); put("message", text) }) }
    }

    // ---------------------------------------------------------------- remote cameras

    /** A camera on another device (the iPhone app) connected to this hub: its connection and last status. */
    private class RemoteCam(val clientId: String, val status: JsonObject)

    private val cams = java.util.concurrent.ConcurrentHashMap<String, RemoteCam>()

    private fun camSource(msg: JsonObject): String =
        msg["source"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotEmpty() && s.length <= 24 && s.all { it.isLetterOrDigit() || it == '-' } }
            ?: "iphone"

    // ---------------------------------------------------------------- leveling with the iPhone

    private val gravitySamples = ArrayDeque<Pair<Long, DoubleArray>>()
    @Volatile private var levelRunning = false

    private fun levelMessage(phase: String, step: Int = 0, message: String = "", result: LevelResult? = null) = buildJsonObject {
        put("type", "level")
        put("phase", phase)
        put("step", step)
        put("of", TelescopeBrain.LEVEL_OFFSETS.size)
        put("message", message)
        result?.let { put("result", levelJson(it)) }
    }

    private fun levelJson(l: LevelResult) = buildJsonObject {
        put("tiltDeg", r(l.tiltDeg))
        brain.solution?.params?.let { p -> put("towardAz", r(l.towardAz(p.azOffset, p.azSign), 1)) }
        put("rmsDeg", r(l.rmsDeg))
        put("at", l.at)
    }

    /** The average of the iPhone's gravity over a short window, once it is steady (the tube still). */
    private suspend fun steadyGravity(): DoubleArray? {
        var last: List<DoubleArray> = emptyList()
        repeat(LEVEL_TRIES) {
            val from = monotonic()
            delay(LEVEL_WINDOW_MS)
            val window = synchronized(gravitySamples) { gravitySamples.filter { it.first >= from }.map { it.second } }
            if (window.size >= LEVEL_MIN_SAMPLES) {
                last = window
                val fronts = window.map { LevelFit.angles(it[0], it[1], it[2]).first }
                if (fronts.max() - fronts.min() < LEVEL_STEADY_DEG) return mean(window)
            }
        }
        return last.takeIf { it.isNotEmpty() }?.let(::mean)
    }

    private fun mean(v: List<DoubleArray>) = DoubleArray(3) { i -> v.sumOf { it[i] } / v.size }

    private fun startLevel() {
        val m = requireMount()
        if (calibrationJob?.isActive == true) throw IllegalStateException("Ya hay una medida en marcha")
        if (brain.mode != PointingMode.STARBRIDGE) throw IllegalStateException("La nivelación es para el modo «Alinea StarBridge»")
        val cam = cams[LEVEL_SOURCE]
            ?: throw IllegalStateException("Abre StarBridge EAA en el iPhone y activa «Compartir la cámara con StarBridge»")
        levelRunning = true
        calibrationJob = scope.launch {
            try {
                synchronized(gravitySamples) { gravitySamples.clear() }
                reply(cam.clientId, buildJsonObject { put("type", "sensorCmd"); put("source", LEVEL_SOURCE); put("cmd", "gravity"); put("on", true) })
                broadcast(levelMessage("preparing", 0, "Deja el iPhone quieto sobre el tubo: el telescopio va a girar en acimut"))
                delay(LEVEL_START_MS)
                if (synchronized(gravitySamples) { gravitySamples.isEmpty() }) {
                    throw IllegalStateException("El iPhone no envía su inclinación: actualiza StarBridge EAA")
                }
                val result = brain.measureLevel(::steadyGravity) { phase, step, text -> broadcast(levelMessage(phase, step, text)) }
                settings.put(KEY_LEVEL, levelStore(result))
                val where = brain.solution?.params?.let { p -> " hacia el ${Astro.compass(result.towardAz(p.azOffset, p.azSign))}" } ?: ""
                broadcast(levelMessage("done", TelescopeBrain.LEVEL_OFFSETS.size,
                    "La base está inclinada ${"%.2f".format(java.util.Locale.ROOT, result.tiltDeg)}°$where: ya basta con una estrella para alinear", result))
                broadcast(alignmentMessage())
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    runCatching { m.emergencyStop() }
                    broadcast(levelMessage("cancelled", 0, "Nivelación cancelada"))
                }
                throw e
            } catch (e: Exception) {
                broadcast(levelMessage("failed", 0, e.message ?: e.toString()))
            } finally {
                levelRunning = false
                withContext(NonCancellable) {
                    reply(cam.clientId, buildJsonObject { put("type", "sensorCmd"); put("source", LEVEL_SOURCE); put("cmd", "gravity"); put("on", false) })
                }
            }
        }
    }

    private fun levelStore(l: LevelResult) = buildJsonObject {
        put("tiltDeg", l.tiltDeg); put("dirDeg", l.dirDeg); put("azSign", l.azSign); put("rmsDeg", l.rmsDeg); put("at", l.at)
    }.toString()

    private fun parseLevel(text: String?): LevelResult? = runCatching {
        val o = json.parseToJsonElement(text?.takeIf { it.isNotBlank() } ?: return null).jsonObject
        LevelResult(o.double("tiltDeg"), o.double("dirDeg"), o.int("azSign"), o.double("rmsDeg"), o["at"]!!.jsonPrimitive.long)
    }.getOrNull()

    // ---------------------------------------------------------------- photos from other cameras

    /** Pictures uploaded by other cameras (the iPhone app): see [PhotoStore]. */
    val photos = PhotoStore()

    /** Stores an upload (`POST /photos`) and tells every page about it. */
    suspend fun addPhoto(params: Map<String, String>, jpeg: ByteArray): PhotoStore.Photo {
        val p = photos.put(params, jpeg, clock())
        broadcast(buildJsonObject {
            put("type", "photo")
            p.toJson().forEach { (k, v) -> put(k, v) }
        })
        return p
    }

    /** `GET /photos/{id}.jpg`: null for a wrong key or an unknown picture. */
    fun photoHttp(id: String, key: String?): PhotoStore.Photo? = if (checkKey(key)) photos.get(id) else null

    private fun photosMessage() = buildJsonObject {
        put("type", "photos")
        putJsonArray("items") { photos.list().forEach { add(it.toJson()) } }
    }

    private suspend fun reply(clientId: String, msg: JsonObject) {
        clients[clientId]?.let { runCatching { it(msg.toString()) } }
    }

    private suspend fun broadcast(msg: JsonObject) {
        val text = msg.toString()
        clients.values.forEach { runCatching { it(text) } }
    }

    companion object {
        const val SLACK_SAMPLE_MS = 50L
        const val SLACK_METER_PUSH_MS = 200L
        /** Speed slider range: from a crawl (½ sidereal) to the motors' top variable speed. */
        const val MIN_SLEW_ARCSEC = 0.5
        const val MAX_SLEW_ARCSEC = 16_000.0
        /** From here up the slider means "as fast as the motors go": fixed rate 9 (≈ 4°/s on an SLT). */
        const val FIXED_TOP_FROM_ARCSEC = 12_000.0
        const val EVENT_LOG_LINES = 120
        const val KEY_GOTO_METHOD = "gotoMethod"
        const val KEY_MANUAL_TAKE_UP = "manualTakeUp"
        /** Hand-control rate 4 ≈ 16× sidereal ≈ 0.067°/s: slow enough to see the star start. */
        const val VISUAL_RATE = 4
        const val VISUAL_RATE_DEG_S = 0.067
        const val VISUAL_PRELOAD_MS = 4_000L
        const val VISUAL_MAX_DEG = 3.0
        const val VISUAL_MAX_MS = 60_000L
        const val VISUAL_REACTION_S = 0.3
        const val KEY_APPROACH = "approachDeg"
        const val KEY_MOUNTING = "mounting"
        const val KEY_ACCESS = "accessKey"
        /** WebSocket close code sent to clients without a valid key. */
        const val CLOSE_UNAUTHORIZED: Short = 4401

        /** 12 characters from a 31-symbol alphabet without look-alikes (~59 random bits). */
        /** Typed codes may have dashes, spaces or capitals. */
        fun normalizeKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        fun newKey(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = java.security.SecureRandom()
            return String(CharArray(12) { alphabet[rnd.nextInt(alphabet.length)] })
        }

        const val KEY_SITE_LAT = "siteLat"
        const val KEY_SITE_LON = "siteLon"
        const val KEY_SLACK_AZ = "slackAz"
        const val KEY_SLACK_ALT = "slackAlt"
        const val KEY_ZONES = "zones"
        const val KEY_LEVEL = "level"
        /** The phone that levels: the iPhone app's camera connection. */
        const val LEVEL_SOURCE = "iphone"
        const val LEVEL_START_MS = 3_000L
        const val LEVEL_WINDOW_MS = 2_000L
        const val LEVEL_TRIES = 4
        const val LEVEL_MIN_SAMPLES = 5
        /** Readings spread less than this inside a window: the tube has stopped shaking. */
        const val LEVEL_STEADY_DEG = 0.08
    }
}
