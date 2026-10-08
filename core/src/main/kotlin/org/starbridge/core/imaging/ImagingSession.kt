package org.starbridge.core.imaging

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.starbridge.core.server.SettingsStore
import org.starbridge.imaging.Calibration
import org.starbridge.imaging.Conversion
import org.starbridge.imaging.DarkBuilder
import org.starbridge.imaging.DenoiseLevel
import org.starbridge.imaging.Export
import org.starbridge.imaging.ExposureAdvisor
import org.starbridge.imaging.ExposureInput
import org.starbridge.imaging.Focus
import org.starbridge.imaging.FrameRecord
import org.starbridge.imaging.Image
import org.starbridge.imaging.LiveStacker
import org.starbridge.imaging.Picture
import org.starbridge.imaging.RawConverter
import org.starbridge.imaging.RawFrame
import org.starbridge.imaging.Renderer
import org.starbridge.imaging.StackConfig
import org.starbridge.imaging.StackStatus
import org.starbridge.imaging.Star
import org.starbridge.imaging.StarDetector
import org.starbridge.imaging.Stats
import org.starbridge.imaging.Verdict
import org.starbridge.imaging.fieldRotationDegPerHour
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The camera side of StarBridge: capture loop, live stacking, assistants (auto exposure,
 * lens focus, telescope focus, exposure optimizer, darks), previews and exports.
 *
 * Threads: captures run in the session's coroutine; every touch of the stacking engine runs
 * on one low-priority worker thread ([cpu]), so the engine never needs locks and the phone
 * stays responsive. While a frame is processed the next exposure is already running, and at
 * most one frame waits (no memory build-up).
 */
class ImagingSession(
    private val scope: CoroutineScope,
    private val source: CameraSource,
    private val host: ImagingHost,
    private val store: SettingsStore,
    private val dataDir: File,
    private val emit: suspend (JsonObject) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Time the mount must be still before a new exposure starts after moving. */
    private val settleMs: Long = 2_000,
    private val pollMs: Long = 250,
    cpu: CoroutineDispatcher? = null,
) {
    enum class Phase(val label: String) {
        IDLE("Parada"),
        PREVIEW("Encuadre"),
        EXPOSURE("Midiendo el cielo"),
        LENS_FOCUS("Enfocando el móvil"),
        STACKING("Apilando"),
        PAUSED("En pausa"),
        DARKS("Haciendo darks"),
        FOCUS("Asistente de enfoque"),
        OPTIMIZE("Optimizando la exposición"),
    }

    /** Auto exposure result for the current camera and setup. */
    data class AutoExposure(
        val exposureSec: Double, val iso: Int, val explanation: String, val limitedBy: String, val readNoise: Double?,
    )

    data class FocusState(
        val hfr: Double = Double.NaN,
        val uncertainty: Double = Double.NaN,
        val stars: Int = 0,
        val reliable: Boolean = false,
        val best: Double = Double.NaN,
        /** measuring · better · worse · focused · nostars */
        val trend: String = "measuring",
        val history: List<Double> = emptyList(),
    )

    private val cpu: CoroutineDispatcher = cpu ?: Executors.newSingleThreadExecutor { r ->
        Thread(r, "StarBridge-imaging").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }.asCoroutineDispatcher()

    /**
     * Memory the stacking engine may use: under half of the app heap, leaving room for the
     * raw frames in flight (a 40 MP RAW alone is 80 MB) and the web server.
     */
    private val memoryBudget: Long = (Runtime.getRuntime().maxMemory() * 0.35).toLong()

    @Volatile var mount: MountLink = MountLink.NONE
    @Volatile var gyro: GyroWindow? = null

    private val darkLib = DarkLibrary(File(dataDir, "darks"))
    private val frameStore = DiskFrameStore(File(dataDir, "rejected"))
    private val exportDir = File(dataDir, "exports").also { it.mkdirs() }
    private val detector = StarDetector()

    private val control = Mutex()
    @Volatile private var job: Job? = null
    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile private var message: String = ""
    @Volatile private var progress: Pair<Int, Int>? = null
    @Volatile private var exposureStartedAt: Long = 0
    @Volatile private var exposureRunning: Double = 0.0

    @Volatile private var camerasCache: List<CameraInfo>? = null
    @Volatile var camera: CameraInfo? = null
        private set
    @Volatile private var selectedId: String? = store.get(KEY_CAMERA)
    @Volatile var settings: ImagingSettings = ImagingSettings()
        private set
    @Volatile private var auto: AutoExposure? = null
    @Volatile private var lensFocus: Double? = null
    @Volatile private var overheadSec = 0.3

    // Engine state: touched only on [cpu].
    private var stacker: LiveStacker? = null
    private var scratch: LiveStacker? = null
    private var focusFrames = ArrayDeque<List<Star>>()
    private var focusHistory = ArrayList<Double>()
    private var sessionStart = 0L
    private var sessionName = ""

    // Published copies (read from any thread).
    @Volatile private var stackStatus: StackStatus? = null
    @Volatile private var records: List<FrameRecord> = emptyList()
    @Volatile private var lastRecord: FrameRecord? = null
    @Volatile private var skippedMoving = 0
    @Volatile private var focus: FocusState? = null
    @Volatile private var lastSaved: List<JsonObject> = emptyList()
    @Volatile private var gotoPaused = false

    private class Img(val version: Int, val bytes: ByteArray)
    private val images = java.util.concurrent.ConcurrentHashMap<String, Img>()
    @Volatile private var imageVersion = 0

    val available: Boolean get() = cameras().isNotEmpty()

    fun cameras(): List<CameraInfo> = camerasCache ?: runCatching { source.cameras() }.getOrDefault(emptyList())
        .also { if (it.isNotEmpty()) camerasCache = it }

    private fun recommended(): CameraInfo? = cameras().maxByOrNull { it.score }

    private fun selectedInfo(): CameraInfo? =
        cameras().firstOrNull { it.id == selectedId } ?: recommended()

    init {
        selectedInfo()?.let { loadCameraSettings(it.id) }
    }

    private fun loadCameraSettings(id: String) {
        settings = ImagingSettings.parse(store.get(KEY_SETTINGS + id))
        lensFocus = store.get(KEY_FOCUS + id)?.toDoubleOrNull()
    }

    // ------------------------------------------------------------------ commands

    /** Handles an "img…" message from a web client. Returns the reply (null = nothing). */
    suspend fun handle(type: String, msg: JsonObject): JsonObject? {
        when (type) {
            "imgState" -> return state()
            "imgCameras" -> return camerasMessage()
            "imgSelect" -> select(msg["id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Falta id"))
            "imgSettings" -> updateSettings(msg["settings"]?.jsonObject ?: throw IllegalArgumentException("Faltan ajustes"))
            "imgStart" -> start()
            "imgPause" -> pause()
            "imgResume" -> resume()
            "imgStop" -> stop()
            "imgReset" -> reset()
            "imgPreview" -> preview()
            "imgFocus" -> if (msg["on"]?.jsonPrimitive?.booleanOrNull == false) stop() else focusAssistant()
            "imgLensFocus" -> lensFocusAgain()
            "imgExposure" -> measureAgain()
            "imgOptimize" -> optimize()
            "imgDarks" -> darks(msg["count"]?.jsonPrimitive?.intOrNull ?: 0)
            "imgDeleteDarks" -> { darkLib.delete(all = true); message = "Darks borrados"; publish() }
            "imgSave" -> return save()
            "imgRecover" -> return recover(msg["id"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Falta id"))
            "imgRecords" -> return recordsMessage()
            else -> throw IllegalArgumentException("Mensaje desconocido: $type")
        }
        return null
    }

    suspend fun select(id: String) {
        val info = cameras().firstOrNull { it.id == id } ?: throw IllegalArgumentException("Cámara desconocida")
        control.withLock {
            job?.cancelAndJoin()
            runCatching { source.close() }
            camera = null
            selectedId = id
            store.put(KEY_CAMERA, id)
            loadCameraSettings(id)
            auto = null
            withContext(cpu) { newStack() }
            phase = Phase.IDLE
            message = "Cámara: ${info.label}"
        }
        publish()
    }

    suspend fun updateSettings(o: JsonObject) {
        val old = settings
        val new = old.merge(o)
        val cam = selectedInfo()
        settings = new
        cam?.let { store.put(KEY_SETTINGS + it.id, new.toJson().toString()) }
        if (new.quality != old.quality) withContext(cpu) { stacker?.setQualityMode(new.quality) }
        val setupChanged = new.binning != old.binning || new.color != old.color || new.isoAuto != old.isoAuto ||
            (!new.isoAuto && new.iso != old.iso)
        if (setupChanged || (new.exposureAuto && !old.exposureAuto)) auto = null
        if (new.needsNewStack(old)) {
            val wasStacking = phase == Phase.STACKING
            control.withLock {
                if (wasStacking) job?.cancelAndJoin()
                withContext(cpu) { newStack() }
                if (wasStacking) launchJob(Phase.STACKING) { prepareAndStack() }
                else if (phase == Phase.PAUSED) phase = Phase.IDLE
            }
            message = if (wasStacking) "Ajustes cambiados: apilado nuevo" else "Ajustes guardados"
        } else if (new.renderChanged(old)) {
            withContext(cpu) { renderAll() }
        }
        publish()
    }

    /** Starts (or resumes) stacking. */
    suspend fun start() {
        if (phase == Phase.STACKING) return
        if (phase == Phase.PAUSED && hasStack()) return resume()
        control.withLock {
            job?.cancelAndJoin()
            withContext(cpu) { newStack() }
            launchJob(Phase.STACKING) { prepareAndStack() }
        }
        publish()
    }

    suspend fun pause() {
        control.withLock {
            if (phase != Phase.STACKING && phase != Phase.EXPOSURE && phase != Phase.LENS_FOCUS) return
            job?.cancelAndJoin()
            phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            message = "En pausa"
            exposureRunning = 0.0
        }
        publish()
    }

    suspend fun resume() {
        control.withLock {
            if (phase == Phase.STACKING) return
            job?.cancelAndJoin()
            gotoPaused = false
            launchJob(Phase.STACKING) { prepareAndStack() }
        }
        publish()
    }

    /** Stops whatever runs; the stack is kept (it can still be saved or resumed). */
    suspend fun stop() {
        control.withLock {
            job?.cancelAndJoin()
            phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            message = ""
            progress = null
            exposureRunning = 0.0
            focus = null
        }
        publish()
    }

    /** Throws the stack away and starts a new one (keeps stacking if it was). */
    suspend fun reset() {
        control.withLock {
            val wasStacking = phase == Phase.STACKING
            job?.cancelAndJoin()
            withContext(cpu) { newStack() }
            gotoPaused = false
            if (wasStacking) launchJob(Phase.STACKING) { prepareAndStack() } else phase = Phase.IDLE
            message = "Apilado nuevo"
        }
        publish()
    }

    /** Framing: continuous single frames, nothing is stacked. */
    suspend fun preview() {
        control.withLock {
            job?.cancelAndJoin()
            launchJob(Phase.PREVIEW) { singlesLoop(focusMode = false) }
        }
        publish()
    }

    suspend fun focusAssistant() {
        control.withLock {
            job?.cancelAndJoin()
            withContext(cpu) { focusFrames.clear(); focusHistory.clear() }
            focus = FocusState()
            launchJob(Phase.FOCUS) { singlesLoop(focusMode = true) }
        }
        publish()
    }

    suspend fun lensFocusAgain() {
        control.withLock {
            job?.cancelAndJoin()
            launchJob(Phase.LENS_FOCUS) {
                ensureCamera()
                val cam = camera ?: return@launchJob
                if (!cam.focusable) { message = "Esta cámara tiene enfoque fijo"; return@launchJob }
                lensFocusSweep(cam)
                phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            }
        }
        publish()
    }

    suspend fun measureAgain() {
        control.withLock {
            job?.cancelAndJoin()
            auto = null
            store.put(readNoiseKeyPrefix(), "") // measure the read noise again too
            launchJob(Phase.EXPOSURE) {
                ensureCamera()
                measureExposure()
                phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            }
        }
        publish()
    }

    suspend fun optimize() {
        control.withLock {
            job?.cancelAndJoin()
            launchJob(Phase.OPTIMIZE) {
                ensureCamera()
                if (auto == null && settings.exposureAuto) measureExposure()
                runOptimizer()
                phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            }
        }
        publish()
    }

    suspend fun darks(countIn: Int) {
        control.withLock {
            job?.cancelAndJoin()
            launchJob(Phase.DARKS) {
                ensureCamera()
                if (auto == null && settings.exposureAuto) {
                    throw IllegalStateException("Primero empieza un apilado (o fija la exposición a mano): los darks se hacen con la misma exposición")
                }
                val t = exposure()
                val count = if (countIn > 0) countIn.coerceIn(3, 60) else (300 / t).roundToInt().coerceIn(10, 30)
                captureDarks(count)
                phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            }
        }
        publish()
    }

    /** Stops everything and closes the camera (service shutting down). */
    suspend fun shutdown() {
        control.withLock {
            job?.cancelAndJoin()
            runCatching { source.close() }
            camera = null
            phase = Phase.IDLE
        }
    }

    // ------------------------------------------------------------------ jobs

    private fun launchJob(p: Phase, block: suspend CoroutineScope.() -> Unit) {
        phase = p
        progress = null
        message = p.label
        job = scope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = e.message ?: e.toString()
                phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
                exposureRunning = 0.0
                runCatching { emit(errorMessage(message)) }
            } finally {
                exposureRunning = 0.0
                progress = null
                // Never IDLE over an existing stack: "Empezar" from IDLE starts a new one.
                if (phase == p && p != Phase.STACKING && p != Phase.PREVIEW && p != Phase.FOCUS) {
                    phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
                }
                // Let a cancelled job publish its final state.
                withContext(NonCancellable) { runCatching { publish() } }
            }
        }
    }

    private fun hasStack(): Boolean = (stackStatus?.frames ?: 0) > 0

    private suspend fun ensureCamera() {
        if (camera != null) return
        val info = selectedInfo() ?: throw IllegalStateException("No hay ninguna cámara disponible")
        message = "Abriendo ${info.label}…"
        publish()
        camera = source.open(info.id)
        selectedId = info.id
    }

    /** Opens the camera, measures the exposure and focuses the lens if needed, then stacks. */
    private suspend fun prepareAndStack() {
        ensureCamera()
        val cam = camera ?: return
        if (settings.exposureAuto && auto == null) measureExposure()
        if (settings.focusAuto && cam.focusable && lensFocus == null) lensFocusSweep(cam)
        phase = Phase.STACKING
        message = if (hasStack()) "Apilando" else "Apilando · aprendiendo el sensor (3 fotos)"
        withContext(cpu) {
            if (stacker == null) newStack()
            darkLib.context = Triple(cam.id, conversion().binning, conversion().color)
            if (sessionStart == 0L) { sessionStart = clock(); sessionName = "Sesion_" + stamp(sessionStart) }
        }
        publish()
        stackLoop()
    }

    private suspend fun stackLoop() = coroutineScope {
        val frames = Channel<RawFrame>(Channel.RENDEZVOUS)
        val worker = launch(cpu) {
            for (raw in frames) withContext(NonCancellable) { processStack(raw) }
        }
        try {
            while (isActive) {
                if (!awaitReady(stacking = true)) break
                val raw = captureOne(stackSpec())
                frames.send(raw)
            }
        } finally {
            frames.close()
        }
        worker.join()
    }

    private suspend fun singlesLoop(focusMode: Boolean) = coroutineScope {
        ensureCamera()
        val cam = camera ?: return@coroutineScope
        withContext(cpu) { scratch = LiveStacker(StackConfig(conversion(), eyepieceMask = true), darkLib, null) }
        val frames = Channel<RawFrame>(Channel.RENDEZVOUS)
        val worker = launch(cpu) {
            for (raw in frames) withContext(NonCancellable) { processSingle(raw, focusMode) }
        }
        try {
            while (isActive) {
                if (!awaitReady(stacking = false)) break
                // Short exposures: fast feedback for framing and focusing. Framing is for pointing:
                // as bright as possible (highest ISO, 1–2 s), quality does not matter there.
                val t = (if (focusMode) min(exposure(), 2.0) else exposure().coerceIn(1.0, 2.0))
                    .coerceIn(cam.minExposureSec, cam.maxExposureSec)
                val iso = if (focusMode) iso() else cam.maxIso
                frames.send(captureOne(CaptureSpec(t, iso, focusDiopters())))
            }
        } finally {
            frames.close()
        }
        worker.join()
    }

    /**
     * Waits until the next exposure may start: mount still, phone not overheating, battery
     * not empty. Returns false if the loop must end (GoTo started, battery empty).
     */
    private suspend fun awaitReady(stacking: Boolean): Boolean {
        if (stacking && mount.gotoActive()) {
            gotoPaused = true
            phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            message = "GoTo en marcha: apilado en pausa. Al llegar, pulsa «Reanudar» (mismo objeto) o «Nuevo»"
            publish()
            return false
        }
        if (mount.moving()) {
            message = "Esperando a que pare el telescopio…"
            publish()
            while (mount.moving()) {
                if (stacking && mount.gotoActive()) return awaitReady(stacking)
                delay(pollMs)
            }
            delay(settleMs)
            message = if (stacking) "Apilando" else phase.label
        }
        val battery = host.battery()
        if (battery != null && battery.first <= 4 && !battery.second) {
            message = "Batería al ${battery.first} %: captura detenida. Conecta un cargador."
            phase = if (hasStack()) Phase.PAUSED else Phase.IDLE
            publish()
            return false
        }
        var thermal = host.thermalStatus()
        if (thermal >= THERMAL_CRITICAL) {
            message = "El móvil está muy caliente: esperando a que se enfríe…"
            publish()
            while (thermal >= THERMAL_SEVERE) {
                delay(5_000)
                thermal = host.thermalStatus()
            }
            message = phase.label
        } else if (thermal == THERMAL_SEVERE) {
            // Rest between exposures: less heat, slightly fewer frames.
            delay((exposure() * 500).toLong().coerceIn(1_000, 15_000))
        }
        return true
    }

    private suspend fun captureOne(spec: CaptureSpec): RawFrame = coroutineScope {
        var moved = mount.moving()
        val watcher = launch {
            while (isActive) {
                if (mount.moving()) moved = true
                delay(pollMs)
            }
        }
        val g = gyro
        g?.begin()
        exposureStartedAt = clock()
        exposureRunning = spec.exposureSec
        publish()
        val t0 = System.nanoTime()
        val raw = try {
            source.capture(spec)
        } finally {
            watcher.cancel()
            exposureRunning = 0.0
        }
        val rms = g?.end()
        source.takeWarning()?.let { w -> runCatching { emit(errorMessage(w)) } }
        val elapsed = (System.nanoTime() - t0) / 1e9
        // Dead time per frame (readout, request setup), for the exposure advisor.
        overheadSec = 0.8 * overheadSec + 0.2 * (elapsed - spec.exposureSec).coerceIn(0.0, 10.0)
        RawFrame(
            raw.width, raw.height, raw.data, raw.cfa, raw.blackLevel, raw.whiteLevel,
            raw.meta.copy(gyroRmsDegPerSec = rms ?: raw.meta.gyroRmsDegPerSec, mountMoving = moved || raw.meta.mountMoving),
        )
    }

    // ------------------------------------------------------------------ setup

    private fun conversion(): Conversion {
        val cam = camera ?: selectedInfo()
        val color = settings.color && cam != null && cam.raw && !cam.mono
        // A manual binning is respected unless the phone would run out of memory with it.
        val bin = if (settings.binning in 1..4) {
            maxOf(settings.binning, cam?.let { autoBinning(it, color, memoryBudget, maxPixels = Long.MAX_VALUE) } ?: 1)
        } else cam?.let { autoBinning(it, color, memoryBudget) } ?: 1
        return Conversion(binning = bin, color = color)
    }

    private fun exposure(): Double {
        val cam = camera ?: selectedInfo()
        val t = if (settings.exposureAuto) auto?.exposureSec ?: settings.exposureSec else settings.exposureSec
        return if (cam == null) t else t.coerceIn(cam.minExposureSec, cam.maxExposureSec)
    }

    private fun iso(): Int {
        val cam = camera ?: selectedInfo()
        val i = if (settings.isoAuto) auto?.iso ?: cam?.let { defaultIso(it) } ?: settings.iso else settings.iso
        return if (cam == null) i else i.coerceIn(cam.minIso, cam.maxIso)
    }

    private fun focusDiopters(): Double = if (settings.focusAuto) lensFocus ?: 0.0 else settings.focusDiopters

    private fun stackSpec() = CaptureSpec(exposure(), iso(), focusDiopters(), settings.keepRaw, sessionName)

    private fun readNoiseKeyPrefix(): String {
        val cam = camera ?: selectedInfo()
        val conv = conversion()
        return "img.rn.${cam?.id}.${iso()}.${conv.binning}.${conv.color}"
    }

    /** Must run on [cpu]. */
    private fun newStack() {
        stacker = LiveStacker(StackConfig(conversion(), qualityMode = settings.quality, eyepieceMask = true), darkLib, frameStore)
        eyepieceCache = null
        frameStore.clear()
        stackStatus = null
        records = emptyList()
        lastRecord = null
        skippedMoving = 0
        sessionStart = 0L
        lastSaved = emptyList()
        images.remove("stack"); images.remove("single"); images.remove("view"); images.remove("thumb")
    }

    // ------------------------------------------------------------------ processing (on cpu)

    private suspend fun processStack(raw: RawFrame) {
        val ls = stacker ?: return
        if (raw.meta.mountMoving) {
            skippedMoving++
            message = "Foto descartada: el telescopio se movía"
            publish()
            return
        }
        val recs = ls.process(raw)
        val st = ls.status()
        stackStatus = st
        records = ls.records.toList()
        recs.lastOrNull()?.let { lastRecord = it }
        if (!st.referenceSet) {
            // Still learning the sensor: show the raw frame so the user sees something.
            val wf = RawConverter.convert(raw, conversion())
            putImage("view", Renderer.render(wf.frame, null, usableMask(wf.frame.luminance), settings.render(PREVIEW_SIZE), 1))
            if (phase == Phase.STACKING) message = "Aprendiendo el sensor (${min(records.size + 1, LiveStacker.LEARN_FRAMES)}/${LiveStacker.LEARN_FRAMES})…"
        } else {
            val thermal = host.thermalStatus()
            // Hot phone: refresh the picture every other frame.
            if (thermal < THERMAL_MODERATE || st.frames % 2 == 0 || images["stack"] == null) renderStack(ls)
            if (images["single"] == null) ls.renderReference(settings.render(PREVIEW_SIZE))?.let { putImage("single", it) }
            // A pause (GoTo, battery) keeps its own message.
            if (phase == Phase.STACKING) message = lastRecord?.let { r ->
                when (r.verdict) {
                    Verdict.REJECT -> "Foto ${r.id} descartada: ${r.reasons.firstOrNull() ?: ""}"
                    Verdict.DOWNWEIGHT -> "Foto ${r.id} añadida con menos peso"
                    Verdict.ACCEPT -> "Apilando"
                }
            } ?: "Apilando"
        }
        publish()
    }

    private suspend fun processSingle(raw: RawFrame, focusMode: Boolean) {
        val ls = scratch ?: return
        val (wf, mask) = ls.calibrate(raw)
        val lum = wf.frame.luminance
        val det = detector.detect(lum, mask, wf.unsaturated, focus?.hfr?.takeIf { !it.isNaN() }?.let { it * 1.2 } ?: 3.0)
        val look = settings.render(PREVIEW_SIZE).let { if (focusMode) it else it.copy(targetBackground = 0.4, denoise = DenoiseLevel.HIGH) }
        putImage("view", Renderer.render(wf.frame, null, mask, look, 1))
        if (focusMode) {
            focusFrames.addLast(det.all)
            while (focusFrames.size > 3) focusFrames.removeFirst()
            val m = Focus.measure(focusFrames.toList())
            val prev = focus ?: FocusState()
            if (!m.hfr.isNaN()) focusHistory.add(m.hfr)
            while (focusHistory.size > 60) focusHistory.removeAt(0)
            val best = focusHistory.minOrNull() ?: Double.NaN
            val trend = when {
                !m.reliable -> "nostars"
                focusHistory.size < 3 -> "measuring"
                m.hfr <= best * 1.05 && focusHistory.takeLast(3).all { it <= best * 1.08 } -> "focused"
                !prev.hfr.isNaN() && m.hfr < prev.hfr - 2 * m.uncertainty -> "better"
                !prev.hfr.isNaN() && m.hfr > prev.hfr + 2 * m.uncertainty -> "worse"
                else -> prev.trend.takeIf { it == "better" || it == "worse" } ?: "measuring"
            }
            focus = FocusState(m.hfr, m.uncertainty, m.stars, m.reliable, best, trend, focusHistory.toList())
            starCrops(lum, det.all)?.let { putImage("stars", it, quality = 90) }
        }
        message = if (focusMode) "Gira el enfocador despacio y mira la nitidez" else "Encuadre: fotos sueltas, no se apila"
        publish()
    }

    private fun renderStack(ls: LiveStacker) {
        val pic = ls.renderStack(settings.render(PREVIEW_SIZE)) ?: return
        refreshCounts(ls)
        putImage("stack", pic)
        putImage("view", pic, version = images["stack"]?.version)
    }

    /** Rendering or exporting may stack frames still held back: keep the published copies in sync. On [cpu]. */
    private fun refreshCounts(ls: LiveStacker) {
        stackStatus = ls.status()
        records = ls.records.toList()
        ls.records.lastOrNull()?.let { lastRecord = it }
    }

    /** Re-renders the current pictures after a look change (stretch, denoise…). Must run on [cpu]. */
    private fun renderAll() {
        val ls = stacker ?: return
        if (ls.status().referenceSet) {
            renderStack(ls)
            ls.renderReference(settings.render(PREVIEW_SIZE))?.let { putImage("single", it) }
        }
    }

    private fun putImage(name: String, pic: Picture, quality: Int = 82, version: Int? = null) {
        val v = version ?: ++imageVersion
        images[name] = Img(v, host.jpeg(pic, quality))
        if (name == "stack") images["thumb"] = Img(v, host.jpeg(thumbnail(pic, 360), 75))
    }

    /** The 3 brightest unsaturated stars, enlarged ×4 side by side (focus assistant). */
    private fun starCrops(lum: Image, stars: List<Star>): Picture? {
        val picks = stars.filter { !it.saturated && it.snr > 15 }.sortedByDescending { it.flux }.take(3)
        if (picks.isEmpty()) return null
        val r = 12
        val z = 4
        val cell = (2 * r + 1) * z
        val w = cell * picks.size + 8 * (picks.size - 1)
        val out = IntArray(w * cell) { 0xFF000000.toInt() }
        picks.forEachIndexed { k, s ->
            val cx = s.x.roundToInt(); val cy = s.y.roundToInt()
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (dy in -r..r) for (dx in -r..r) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until lum.width && y in 0 until lum.height) {
                    val v = lum.data[y * lum.width + x]
                    if (!v.isNaN()) { lo = min(lo, v); hi = max(hi, v) }
                }
            }
            val span = max(1e-6f, hi - lo)
            for (oy in 0 until cell) for (ox in 0 until cell) {
                val x = cx - r + ox / z; val y = cy - r + oy / z
                val v = if (x in 0 until lum.width && y in 0 until lum.height) lum.data[y * lum.width + x] else lo
                val g = (sqrt(((v - lo) / span).coerceIn(0f, 1f)) * 255).roundToInt()
                out[oy * w + k * (cell + 8) + ox] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return Picture(w, cell, out)
    }

    private fun thumbnail(p: Picture, size: Int): Picture {
        val k = max(1, kotlin.math.ceil(max(p.width, p.height).toDouble() / size).toInt())
        val w = p.width / k
        val h = p.height / k
        return Picture(w, h, IntArray(w * h) { i -> p.argb[(i / w) * k * p.width + (i % w) * k] })
    }

    // ------------------------------------------------------------------ assistants

    /**
     * Area to measure in a quick, uncalibrated frame: inside the eyepiece circle if there is
     * one (its bright edge would otherwise look like hundreds of fat "stars"). On [cpu].
     */
    private var eyepieceCache: Pair<Pair<Int, Int>, org.starbridge.imaging.Mask>? = null

    private fun usableMask(lum: Image): org.starbridge.imaging.Mask {
        eyepieceCache?.let { (size, m) -> if (size == lum.width to lum.height) return m }
        val m = Calibration.eyepieceMask(lum) ?: Calibration.borderMask(lum.width, lum.height)
        eyepieceCache = (lum.width to lum.height) to m
        return m
    }

    /**
     * Auto exposure: read noise from two shortest frames (cached per camera/ISO/binning),
     * one test frame for the sky noise, saturation and background; then the advisor
     * (sky swamps read noise, limited by camera, field rotation, saturation, bright sky).
     */
    private suspend fun measureExposure() {
        val cam = camera ?: return
        phase = Phase.EXPOSURE
        message = "Midiendo el cielo para elegir la exposición…"
        publish()
        val iso = if (settings.isoAuto) defaultIso(cam) else settings.iso.coerceIn(cam.minIso, cam.maxIso)
        if (!cam.manual) {
            auto = AutoExposure(cam.maxExposureSec, iso, "Esta cámara no deja fijar la exposición: la elige el móvil", "la cámara", null)
            return
        }
        val conv = conversion()
        val rnKey = "img.rn.${cam.id}.$iso.${conv.binning}.${conv.color}"
        var rn = store.get(rnKey)?.toDoubleOrNull()
        if (rn == null) {
            val tMin = max(cam.minExposureSec, 0.001)
            progress = 0 to 3
            val a = captureOne(CaptureSpec(tMin, iso, focusDiopters()))
            progress = 1 to 3
            val b = captureOne(CaptureSpec(tMin, iso, focusDiopters()))
            rn = withContext(cpu) {
                ExposureAdvisor.readNoise(RawConverter.convert(a, conv).frame.luminance, RawConverter.convert(b, conv).frame.luminance)
            }
            if (rn > 0) store.put(rnKey, rn.toString())
        }
        progress = 2 to 3
        val t0 = min(2.0, cam.maxExposureSec).coerceAtLeast(cam.minExposureSec)
        val test = captureOne(CaptureSpec(t0, iso, focusDiopters()))
        progress = 3 to 3
        val readNoise = rn
        val result = withContext(cpu) {
            val wf = RawConverter.convert(test, conv)
            val lum = wf.frame.luminance
            val mask = usableMask(lum)
            val det = detector.detect(lum, mask, wf.unsaturated)
            val sigma = Stats.robustNoise(lum, mask).takeIf { !it.isNaN() && it > 0 } ?: det.background.sigma.toDouble()
            val level = det.background.level.toDouble()
            val range = wf.saturationLevel.toDouble()
            val pointing = mount.pointing()
            val lat = mount.latitude()
            val rotation = if (mount.connected() && pointing != null && lat != null) {
                fieldRotationDegPerHour(lat, pointing.first, pointing.second)
            } else 0.0
            val advice = ExposureAdvisor.advise(ExposureInput(
                testExposureSec = t0, backgroundSigma = sigma, readNoise = readNoise, minExposureSec = cam.minExposureSec,
                maxExposureSec = cam.maxExposureSec, rotationDegPerHour = rotation,
                maxRadiusPx = hypot(lum.width.toDouble(), lum.height.toDouble()) / 2,
                fwhmPx = det.stats.medianFwhm.takeIf { !it.isNaN() } ?: 3.0,
                saturatedFraction = det.stats.saturatedFraction.takeIf { !it.isNaN() } ?: 0.0,
                overheadSec = overheadSec,
            ))
            // Bright sky (moon, city): keep the background under 25 % of the range.
            val tBright = if (level > 0 && range > 0) t0 * 0.25 * range / level else Double.MAX_VALUE
            if (tBright < advice.exposureSec) {
                val t = niceExposure(tBright.coerceIn(cam.minExposureSec, cam.maxExposureSec))
                AutoExposure(t, iso, "%s s · limitado por el brillo del cielo (fondo al 25 %%)".format(fmt(t)), "el brillo del cielo", readNoise)
            } else {
                val t = niceExposure(advice.exposureSec)
                AutoExposure(t, iso, advice.explanation.replaceFirst(Regex("^[0-9.,]+ s"), "${fmt(t)} s"), advice.limitedBy, readNoise)
            }
        }
        auto = result
        message = "Exposición: ${result.explanation} · ISO ${result.iso}"
        publish()
    }

    /**
     * Lens focus: a coarse sweep near infinity finds the region of the minimum HFR, a fine
     * sweep around it is fitted with the V curve (only the points near the bottom: far from
     * focus the HFR of big donuts flattens out and would bend the fit).
     */
    private suspend fun lensFocusSweep(cam: CameraInfo) {
        phase = Phase.LENS_FOCUS
        val maxD = min(cam.minFocusDiopters, 2.0)
        if (maxD <= 0) return
        val t = min(exposure(), 2.0).coerceIn(cam.minExposureSec, cam.maxExposureSec)
        val conv = conversion()
        val coarseStep = maxD / 8
        val coarse = (0..8).map { coarseStep * it }
        val total = coarse.size + 7
        var done = 0
        suspend fun measure(p: Double): Double {
            progress = done to total
            message = "Enfocando el móvil: buscando la mayor nitidez (${done + 1}/$total)"
            publish()
            val raw = captureOne(CaptureSpec(t, iso(), p))
            done++
            return withContext(cpu) {
                val wf = RawConverter.convert(raw, conv)
                val lum = wf.frame.luminance
                val det = detector.detect(lum, usableMask(lum), wf.unsaturated)
                Focus.measure(listOf(det.all)).let { if (it.reliable) it.hfr else Double.NaN }
            }
        }
        val coarsePts = coarse.map { it to measure(it) }.filter { !it.second.isNaN() }
        val roughBest = coarsePts.minByOrNull { it.second }?.first
        val best = if (roughBest == null) null else {
            val fine = (-3..3).map { (roughBest + it * coarseStep / 3).coerceIn(0.0, maxD) }.distinct()
            val pts = (fine.map { it to measure(it) } + coarsePts).filter { !it.second.isNaN() }
            val minH = pts.minOf { it.second }
            val near = pts.filter { it.second <= minH * 1.8 && abs(it.first - roughBest) <= coarseStep * 1.01 }
            Focus.bestPosition(near.map { it.first }, near.map { it.second })
                ?.takeIf { abs(it - roughBest) <= coarseStep }
                ?: pts.minBy { it.second }.first
        }
        if (best == null) {
            lensFocus = 0.0
            message = "No se vieron estrellas para enfocar el móvil: se queda en infinito"
        } else {
            lensFocus = best
            store.put(KEY_FOCUS + cam.id, best.toString())
            message = "Móvil enfocado (%.2f dioptrías)".format(Locale.US, best)
        }
        publish()
    }

    /** Tries 3 exposures around the current one on the real sky; keeps the best SNR per minute. */
    private suspend fun runOptimizer() {
        val cam = camera ?: return
        if (!cam.manual) throw IllegalStateException("Esta cámara no deja fijar la exposición")
        val base = exposure()
        val candidates = listOf(base * 0.5, base, base * 2).map { niceExposure(it.coerceIn(cam.minExposureSec, cam.maxExposureSec)) }.distinct()
        val perFrame = 3
        val total = candidates.size * perFrame
        var done = 0
        val conv = conversion()
        val scores = ArrayList<Pair<Double, Double>>()
        for (t in candidates) {
            val snrs = ArrayList<Double>()
            repeat(perFrame) {
                progress = done to total
                message = "Probando ${fmt(t)} s (${done + 1}/$total)"
                publish()
                val raw = captureOne(CaptureSpec(t, iso(), focusDiopters()))
                done++
                val s = withContext(cpu) {
                    val wf = RawConverter.convert(raw, conv)
                    val lum = wf.frame.luminance
                    val det = detector.detect(lum, usableMask(lum), wf.unsaturated)
                    val top = det.all.filter { !it.saturated }.sortedByDescending { it.flux }.take(15)
                    if (top.size < 3) Double.NaN else Stats.median(top.map { it.snr })
                }
                if (!s.isNaN()) snrs += s
            }
            if (snrs.isNotEmpty()) scores += t to Stats.median(snrs) * sqrt(60.0 / (t + overheadSec))
        }
        val best = scores.maxByOrNull { it.second } ?: throw IllegalStateException("No se vieron estrellas suficientes para comparar")
        auto = AutoExposure(best.first, iso(), "${fmt(best.first)} s · probada en tu cielo: la mejor señal por minuto", "medida real", auto?.readNoise)
        if (!settings.exposureAuto) {
            settings = settings.copy(exposureAuto = true)
            selectedInfo()?.let { store.put(KEY_SETTINGS + it.id, settings.toJson().toString()) }
        }
        message = "Exposición optimizada: ${fmt(best.first)} s"
        if (hasStack()) withContext(cpu) { newStack() } // a different exposure means a new stack
        publish()
    }

    private suspend fun captureDarks(count: Int) {
        val cam = camera ?: return
        val spec = CaptureSpec(exposure(), iso(), focusDiopters())
        val conv = conversion()
        val builder = DarkBuilder(spec.exposureSec, spec.iso)
        for (i in 0 until count) {
            progress = i to count
            message = "Darks: foto ${i + 1} de $count (telescopio tapado)"
            publish()
            val raw = captureOne(spec)
            withContext(cpu) { builder.add(RawConverter.convert(raw, conv).frame) }
        }
        val dark = withContext(cpu) { builder.build() } ?: return
        darkLib.save(DarkLibrary.Key(cam.id, spec.iso, spec.exposureSec, conv.binning, conv.color), dark)
        message = "Darks guardados: $count fotos de ${fmt(spec.exposureSec)} s · ISO ${spec.iso}. Ya puedes destapar el telescopio."
        publish()
    }

    // ------------------------------------------------------------------ results

    suspend fun save(): JsonObject = withContext(cpu) {
        val ls = stacker ?: throw IllegalStateException("Todavía no hay nada apilado")
        val frame = ls.stackFrame() ?: throw IllegalStateException("Todavía no hay nada apilado")
        refreshCounts(ls)
        val st = ls.status()
        val cam = camera ?: selectedInfo()
        val target = mount.target()
        val name = "StarBridge_" + (target?.replace(Regex("[^A-Za-z0-9]+"), "-")?.trim('-')?.plus("_") ?: "") + stamp(clock())
        val header = linkedMapOf(
            "EXPTIME" to "%.2f".format(Locale.US, st.integrationSec),
            "NCOMBINE" to "${st.accepted + st.downweighted}",
            "DATE-OBS" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(if (sessionStart > 0) sessionStart else clock())),
            "INSTRUME" to (cam?.label ?: "phone"),
            "XBINNING" to "${conversion().binning}",
            "ISOSPEED" to "${iso()}",
            "SWCREATE" to "StarBridge",
        )
        target?.let { header["OBJECT"] = it }
        mount.latitude()?.let { header["SITELAT"] = "%.4f".format(Locale.US, it) }
        val files = ArrayList<JsonObject>()
        fun write(ext: String, mime: String, bytes: ByteArray, label: String) {
            val f = File(exportDir, "$name.$ext")
            f.writeBytes(bytes)
            val where = runCatching { host.publish(f, mime) }.getOrDefault(f.absolutePath)
            files += buildJsonObject {
                put("name", f.name); put("label", label); put("bytes", bytes.size); put("url", "img/file/${f.name}"); put("where", where)
            }
        }
        val pic = ls.renderStack(settings.render(8192))
        if (pic != null) write("jpg", "image/jpeg", host.jpeg(pic, 95), "JPEG · tal como se ve")
        val (cropped, unit) = Export.linearUnit(frame, ls.stackCoverage(), ls.usableMask)
        write("tif", "image/tiff", Export.tiff16(cropped.width, cropped.height, unit), "TIFF 16 bits · lineal, para Lightroom/Photoshop")
        write("fits", "image/fits", Export.fits(frame, header), "FITS 32 bits · lineal, para Siril/PixInsight")
        // Keep only the last few exports in the app folder (the published copies stay).
        exportDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
        lastSaved = files
        message = "Guardado: ${files.size} archivos"
        val reply = buildJsonObject {
            put("type", "imgSaved")
            putJsonArray("files") { files.forEach { add(it) } }
            files.firstOrNull()?.get("where")?.let { put("where", it) }
        }
        reply
    }.also { publish() }

    suspend fun recover(id: Int): JsonObject {
        val rec = withContext(cpu) {
            val ls = stacker ?: throw IllegalStateException("No hay apilado")
            val r = ls.recover(id) ?: throw IllegalStateException("Esa foto ya no se puede recuperar")
            stackStatus = ls.status()
            records = ls.records.toList()
            renderStack(ls)
            r
        }
        message = "Foto ${rec.id} recuperada"
        publish()
        return recordsMessage()
    }

    fun recordsMessage(): JsonObject {
        val list = records
        val t0 = list.firstOrNull()?.timestampMs ?: 0L
        return buildJsonObject {
            put("type", "imgRecords")
            putJsonArray("items") {
                list.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("t", ((r.timestampMs - t0) / 1000.0).let { if (it.isNaN() || it < 0) 0.0 else r1(it) })
                        put("v", when (r.verdict) { Verdict.ACCEPT -> "accept"; Verdict.DOWNWEIGHT -> "down"; Verdict.REJECT -> "reject" })
                        put("w", r2(r.weight))
                        putJsonArray("reasons") { r.reasons.forEach { add(JsonPrimitive(it)) } }
                        r.metrics?.let { m ->
                            num("fwhm", m.fwhm, 2); put("stars", m.stars); num("transp", m.transparency, 2)
                        }
                        put("gain", r2(r.snrGain))
                        put("ms", r.processingMs)
                        put("recoverable", r.verdict == Verdict.REJECT && frameStore.has(r.id))
                    })
                }
            }
        }
    }

    fun camerasMessage(): JsonObject {
        val rec = recommended()?.id
        return buildJsonObject {
            put("type", "imgCameras")
            put("selected", selectedInfo()?.id)
            put("recommended", rec)
            putJsonArray("items") { cameras().forEach { add(cameraJson(it)) } }
        }
    }

    private fun cameraJson(c: CameraInfo) = buildJsonObject {
        put("id", c.id); put("label", c.label); put("facing", c.facing)
        put("raw", c.raw); put("manual", c.manual); put("mono", c.mono)
        put("w", c.width); put("h", c.height); put("mp", r1(c.megapixels))
        put("minExp", c.minExposureSec); put("maxExp", c.maxExposureSec)
        put("minIso", c.minIso); put("maxIso", c.maxIso); c.maxAnalogIso?.let { put("analogIso", it) }
        put("focusable", c.focusable); put("minFocusD", c.minFocusDiopters)
        c.focalLengthMm?.let { put("focalMm", r2(it)) }
        putJsonArray("notes") { c.notes.forEach { add(JsonPrimitive(it)) } }
    }

    /** Full state for the web (broadcast after every frame and change). */
    fun state(): JsonObject {
        val cam = camera ?: selectedInfo()
        val st = stackStatus
        val conv = conversion()
        return buildJsonObject {
            put("type", "imaging")
            put("available", cam != null)
            put("phase", phase.name.lowercase())
            put("phaseLabel", phase.label)
            put("message", message)
            put("now", clock())
            progress?.let { (d, t) -> putJsonObject("progress") { put("done", d); put("total", t) } }
            if (exposureRunning > 0) putJsonObject("exposing") { put("startedAt", exposureStartedAt); put("sec", exposureRunning) }
            cam?.let { put("camera", cameraJson(it)) }
            put("cameraOpen", camera != null)
            put("settings", settings.toJson())
            putJsonObject("effective") {
                put("exposureSec", exposure()); put("iso", iso()); put("binning", conv.binning); put("color", conv.color)
                put("focusDiopters", r2(focusDiopters()))
                cam?.let { c ->
                    var w = c.width; var h = c.height
                    if (c.raw && !c.mono) { w /= 2; h /= 2 }
                    put("width", w / conv.binning); put("height", h / conv.binning)
                }
            }
            auto?.let { a ->
                putJsonObject("auto") {
                    put("exposureSec", a.exposureSec); put("iso", a.iso); put("explanation", a.explanation); put("limitedBy", a.limitedBy)
                    a.readNoise?.let { num("readNoise", it, 2) }
                }
            }
            if (st != null) putJsonObject("stack") {
                put("frames", st.frames); put("accepted", st.accepted); put("downweighted", st.downweighted); put("rejected", st.rejected)
                put("integrationSec", r1(st.integrationSec)); num("snrGain", st.snrGain, 2); num("magGain", st.magGain, 2)
                num("singleFwhm", st.singleFwhm, 2); num("stackFwhm", st.stackFwhm, 2)
                put("plateau", st.plateau); put("referenceSet", st.referenceSet); put("defects", st.defects)
                put("skippedMoving", skippedMoving)
                putJsonArray("lastReasons") { st.lastReasons.forEach { add(JsonPrimitive(it)) } }
            }
            lastRecord?.let { r ->
                putJsonObject("last") {
                    put("id", r.id); put("verdict", r.verdict.name.lowercase()); put("ms", r.processingMs); put("w", r2(r.weight))
                    putJsonArray("reasons") { r.reasons.forEach { add(JsonPrimitive(it)) } }
                }
            }
            putJsonObject("images") { images.forEach { (k, v) -> put(k, v.version) } }
            focus?.let { f ->
                putJsonObject("focus") {
                    num("hfr", f.hfr, 2); num("uncertainty", f.uncertainty, 2); num("best", f.best, 2)
                    put("stars", f.stars); put("reliable", f.reliable); put("trend", f.trend)
                    putJsonArray("history") { f.history.forEach { add(JsonPrimitive(r2(it))) } }
                }
            }
            lensFocus?.let { put("lensFocus", r2(it)) }
            putJsonObject("darks") {
                val c = cam
                put("count", darkLib.list().size)
                put("matching", c != null && darkLib.has(c.id, iso(), exposure(), conv.binning, conv.color))
            }
            putJsonObject("health") {
                put("thermal", host.thermalStatus())
                host.battery()?.let { (p, ch) -> put("battery", p); put("charging", ch) }
            }
            put("mount", mount.connected())
            put("gotoPaused", gotoPaused)
            mount.target()?.let { put("target", it) }
            if (lastSaved.isNotEmpty()) putJsonArray("saved") { lastSaved.forEach { add(it) } }
        }
    }

    private suspend fun publish() {
        runCatching { emit(state()) }
    }

    /** Pictures and exported files over HTTP (the server checks the access key first). */
    fun http(path: String): HttpFile? {
        val p = path.trimStart('/')
        if (p.startsWith("file/")) {
            val name = p.removePrefix("file/")
            if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
            val f = File(exportDir, name)
            if (!f.isFile) return null
            val type = when (f.extension.lowercase()) {
                "jpg" -> "image/jpeg"; "tif" -> "image/tiff"; "fits" -> "application/fits"; else -> "application/octet-stream"
            }
            return HttpFile(f.readBytes(), type, f.name)
        }
        val img = images[p.removeSuffix(".jpg")] ?: return null
        return HttpFile(img.bytes, "image/jpeg")
    }

    private fun errorMessage(text: String) = buildJsonObject { put("type", "error"); put("message", text) }

    companion object {
        const val PREVIEW_SIZE = 1280
        const val THERMAL_MODERATE = 2
        const val THERMAL_SEVERE = 3
        const val THERMAL_CRITICAL = 4
        private const val KEY_CAMERA = "img.camera"
        private const val KEY_SETTINGS = "img.settings."
        private const val KEY_FOCUS = "img.focus."
        /** Working frames up to ~2.6 MP: fast on any phone, still plenty of detail. */
        const val MAX_WORKING_PIXELS = 2_600_000

        /**
         * Peak memory of the stacking engine per working pixel (bytes): per channel the
         * running mean, variance, weight and count (14 B), the 5 warm-up frames kept for the
         * robust first re-stack (5 × 4 B + coverage), plus the frame being warped.
         */
        fun bytesPerPixel(color: Boolean): Long = if (color) 170 else 70

        /** Smallest binning whose working frame fits in [MAX_WORKING_PIXELS] and in [memoryBytes]. */
        fun autoBinning(
            c: CameraInfo, color: Boolean = false, memoryBytes: Long = Long.MAX_VALUE,
            maxPixels: Long = MAX_WORKING_PIXELS.toLong(),
        ): Int {
            var w = c.width
            var h = c.height
            if (c.raw && !c.mono) { w /= 2; h /= 2 }
            val byMemory = memoryBytes / bytesPerPixel(color)
            val limit = minOf(maxPixels, byMemory)
            for (b in 1..4) if ((w / b).toLong() * (h / b) <= limit) return b
            return 4
        }

        /** Highest analog ISO: lowest read noise without losing dynamic range for nothing. */
        fun defaultIso(c: CameraInfo): Int = (c.maxAnalogIso ?: 800).coerceIn(c.minIso, c.maxIso)

        /** Rounds to values people read easily: 0.1 s below 2 s, 0.5 s below 10 s, 1 s above. */
        fun niceExposure(t: Double): Double = when {
            t < 0.1 -> t
            t < 2 -> Math.round(t * 10) / 10.0
            t < 10 -> Math.round(t * 2) / 2.0
            else -> Math.round(t).toDouble()
        }

        fun fmt(t: Double): String = if (t >= 10 || t == Math.floor(t)) "%.0f".format(Locale.US, t) else if (t >= 0.1) "%.1f".format(Locale.US, t) else "%.3f".format(Locale.US, t)

        private fun stamp(ms: Long) = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ms))
        private fun r1(x: Double) = Math.round(x * 10) / 10.0
        private fun r2(x: Double) = Math.round(x * 100) / 100.0
        private fun kotlinx.serialization.json.JsonObjectBuilder.num(k: String, v: Double, digits: Int) {
            if (!v.isNaN() && !v.isInfinite()) put(k, if (digits == 1) r1(v) else r2(v))
        }
    }
}
