package org.starbridge.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.starbridge.core.imaging.CameraInfo
import org.starbridge.core.imaging.CameraSource
import org.starbridge.core.imaging.CaptureSpec
import org.starbridge.imaging.Cfa
import org.starbridge.imaging.FrameMeta
import org.starbridge.imaging.RawFrame
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Any Android camera through Camera2: every logical camera plus the physical cameras behind
 * logical multi-cameras (e.g. a monochrome sensor). RAW_SENSOR when available (otherwise the
 * luma of YUV frames), fully manual long exposures, focus locked, every in-camera
 * processing (noise reduction, hot pixels, sharpening, stabilization) switched off: the
 * stacking engine does it properly.
 */
class Camera2Source(private val context: Context) : CameraSource {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("StarBridge-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val lock = Mutex()

    private class Opened(
        val info: CameraInfo,
        val chars: CameraCharacteristics,
        val device: CameraDevice,
        val session: CameraCaptureSession,
        val reader: ImageReader,
        val raw: Boolean,
        /** Set when a physical camera behind a logical multi-camera is used. */
        val physicalId: String?,
    )

    @Volatile private var opened: Opened? = null
    private var cache: List<Pair<CameraInfo, CameraCharacteristics>>? = null

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun cameras(): List<CameraInfo> = list().map { it.first }

    private fun list(): List<Pair<CameraInfo, CameraCharacteristics>> {
        cache?.let { return it }
        if (!hasPermission()) return emptyList()
        val out = ArrayList<Pair<CameraInfo, CameraCharacteristics>>()
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        var backIndex = 0
        for (id in ids) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            describe(id, ch, ++backIndex)?.let { out += it to ch }
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                for (pid in ch.physicalCameraIds) {
                    if (pid in ids) continue // already listed on its own
                    val pch = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    describe("$id/$pid", pch, ++backIndex)?.let { out += it to pch }
                }
            }
        }
        if (out.isNotEmpty()) cache = out
        return out
    }

    private fun describe(id: String, ch: CameraCharacteristics, n: Int): CameraInfo? {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return null // depth-only
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val rawSizes = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
        } else emptyList()
        val raw = rawSizes.isNotEmpty()
        val size = (if (raw) rawSizes else map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty())
            .maxByOrNull { it.width.toLong() * it.height } ?: return null
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val mono = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in caps ||
            cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO ||
            cfa == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble()
        val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> "front"
            CameraMetadata.LENS_FACING_EXTERNAL -> "external"
            else -> "back"
        }
        val maxExp = if (manual && exp != null) exp.upper / 1e9 else 0.25
        val mp = size.width * size.height / 1e6
        val label = buildString {
            append(when (facing) { "front" -> "Delantera"; "external" -> "Externa"; else -> "Trasera" })
            append(if (mono) " · monocromo" else " · color")
            append(String.format(Locale("es", "ES"), " %.0f MP", mp))
            focal?.let { append(String.format(Locale("es", "ES"), " · %.1f mm", it)) }
            if (id.contains('/')) append(" (id $id)")
        }
        val notes = buildList {
            if (!raw) add("sin RAW: se usa la imagen procesada")
            if (!manual) add("sin exposición manual")
            else if (maxExp < 2) add(String.format(Locale("es", "ES"), "exposición máx. %.2f s", maxExp))
            if (minFocus == 0f) add("enfoque fijo")
        }
        return CameraInfo(
            id = id, label = label, facing = facing, raw = raw, manual = manual, mono = mono,
            width = size.width, height = size.height,
            minExposureSec = if (manual && exp != null) exp.lower / 1e9 else 0.001,
            maxExposureSec = maxExp,
            minIso = iso?.lower ?: 100, maxIso = iso?.upper ?: 3200,
            maxAnalogIso = ch.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
            focusable = minFocus > 0f, minFocusDiopters = minFocus.toDouble(), focalLengthMm = focal,
            notes = notes,
        )
    }

    override suspend fun open(id: String): CameraInfo = lock.withLock { openLocked(id) }

    @SuppressLint("MissingPermission")
    private suspend fun openLocked(id: String): CameraInfo {
        closeLocked()
        if (!hasPermission()) throw IllegalStateException("Falta el permiso de cámara: ábrelo en la app StarBridge del Android")
        val (info, chars) = list().firstOrNull { it.first.id == id } ?: throw IllegalArgumentException("Cámara $id no disponible")
        val logicalId = id.substringBefore('/')
        val physicalId = id.substringAfter('/', "").ifEmpty { null }
        val device = suspendCancellableCoroutine<CameraDevice> { cont ->
            manager.openCamera(logicalId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Otra app está usando la cámara"))
                    else onLost(camera)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Error al abrir la cámara ($error)"))
                    else onLost(camera)
                }
            })
        }
        var reader: ImageReader? = null
        try {
            val format = if (info.raw) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
            val r = ImageReader.newInstance(info.width, info.height, format, 2).also { reader = it }
            val output = OutputConfiguration(r.surface).apply { physicalId?.let { setPhysicalCameraId(it) } }
            val session = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                device.createCaptureSession(SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(output), executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) else s.close() }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (cont.isActive) cont.resumeWithException(IllegalStateException(
                                "Esta cámara no admite ${if (info.raw) "RAW" else "esta resolución"} así: prueba otra en Ajustes",
                            ))
                        }
                    },
                ))
            }
            opened = Opened(info, chars, device, session, r, info.raw, physicalId)
            lastId = id
            lastFocus = Double.NaN
            return info
        } catch (e: Throwable) {
            runCatching { reader?.close() }
            device.close()
            throw e
        }
    }

    /** Last camera opened: reopened by itself if the system took it away (another app, face unlock…). */
    @Volatile private var lastId: String? = null
    private var lastFocus = Double.NaN

    /** Camera lost after opening: release everything; the next capture reopens it. */
    private fun onLost(camera: CameraDevice) {
        val o = opened ?: return
        if (o.device != camera) return
        opened = null
        runCatching { o.session.close() }
        runCatching { o.reader.close() }
    }

    @Volatile private var warning: String? = null

    override fun takeWarning(): String? = warning.also { warning = null }

    override suspend fun capture(spec: CaptureSpec): RawFrame = lock.withLock {
        val o = opened ?: lastId?.let { openLocked(it); opened }
            ?: throw IllegalStateException("La cámara se ha cerrado (¿otra app la está usando?)")
        // The lens takes a few frames to reach a new focus: a short throwaway frame first.
        if (o.info.focusable && spec.focusDiopters != lastFocus) {
            runCatching { shoot(o, spec.copy(exposureSec = minOf(spec.exposureSec, 0.2), keepRaw = false), lockAe = false) }
            lastFocus = spec.focusDiopters
        }
        val lockAe = if (!o.info.manual) settleAutoExposure(o) else false
        shoot(o, spec, lockAe)
    }

    /** Builds a request with every in-camera processing off (the stacking engine does it right). */
    private fun request(o: Opened, spec: CaptureSpec, template: Int): CaptureRequest.Builder {
        val info = o.info
        val ch = o.chars
        val pid = o.physicalId
        val b = if (pid != null) o.device.createCaptureRequest(template, setOf(pid)) else o.device.createCaptureRequest(template)
        b.addTarget(o.reader.surface)
        // With a physical camera the settings go to the logical camera and to the physical one.
        fun <T> put(key: CaptureRequest.Key<T>, v: T) {
            b.set(key, v)
            if (pid != null) runCatching { b.setPhysicalCameraKey(key, v, pid) }
        }
        if (info.manual) {
            val expNs = (spec.exposureSec.coerceIn(info.minExposureSec, info.maxExposureSec) * 1e9).toLong()
            put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            put(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
            put(CaptureRequest.SENSOR_FRAME_DURATION, expNs)
            put(CaptureRequest.SENSOR_SENSITIVITY, spec.iso.coerceIn(info.minIso, info.maxIso))
        } else {
            put(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            put(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        put(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        if (info.focusable) put(CaptureRequest.LENS_FOCUS_DISTANCE, spec.focusDiopters.toFloat().coerceIn(0f, info.minFocusDiopters.toFloat()))
        fun has(key: CameraCharacteristics.Key<IntArray>, v: Int) = ch.get(key)?.contains(v) == true
        if (has(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CameraMetadata.NOISE_REDUCTION_MODE_OFF)) {
            put(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        }
        if (has(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CameraMetadata.EDGE_MODE_OFF)) {
            put(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        }
        if (has(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CameraMetadata.HOT_PIXEL_MODE_OFF)) {
            put(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        }
        if (has(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
            put(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        if (has(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            put(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
        return b
    }

    /**
     * Cameras without manual control: a short preview burst lets auto exposure converge
     * (a lone still would come out at an arbitrary exposure). Returns true to lock AE.
     */
    private suspend fun settleAutoExposure(o: Opened): Boolean {
        val b = request(o, CaptureSpec(0.1, 800), CameraDevice.TEMPLATE_PREVIEW)
        val done = CompletableDeferred<Unit>()
        // Preview frames land in the reader too: drop them.
        o.reader.setOnImageAvailableListener({ r -> runCatching { r.acquireNextImage()?.close() } }, handler)
        return try {
            o.session.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (ae == null || ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                        ae == CameraMetadata.CONTROL_AE_STATE_LOCKED || ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) done.complete(Unit)
                }
            }, handler)
            withTimeoutOrNull(3_000) { done.await() }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            runCatching { o.session.stopRepeating() }
            o.reader.setOnImageAvailableListener(null, null)
        }
    }

    /** One exposure. The image is matched to its request by the sensor timestamp. */
    private suspend fun shoot(o: Opened, spec: CaptureSpec, lockAe: Boolean): RawFrame {
        val template = if (o.info.manual) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE
        val b = request(o, spec, template)
        if (lockAe) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        val pid = o.physicalId
        val imageReady = CompletableDeferred<Image>()
        val resultReady = CompletableDeferred<TotalCaptureResult>()
        // Sensor timestamp of *this* exposure (from onCaptureStarted, always before its image):
        // a late image of an aborted earlier capture never gets paired with this result.
        var expected = Long.MIN_VALUE
        while (true) { o.reader.acquireLatestImage()?.close() ?: break }
        o.reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (img.timestamp != expected || !imageReady.complete(img)) img.close()
        }, handler)
        o.session.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
                expected = timestamp // same handler thread as the image listener
            }
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                resultReady.complete(result)
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                resultReady.completeExceptionally(IllegalStateException("La foto falló (${f.reason})"))
                imageReady.completeExceptionally(IllegalStateException("La foto falló"))
            }
        }, handler)
        var image: Image? = null
        try {
            val timeout = (spec.exposureSec * 1000).toLong() + 20_000
            val total = withTimeout(timeout) { resultReady.await() }
            image = withTimeout(10_000) { imageReady.await() }
            // A physical camera reports its own exposure and black/white levels.
            val result: CaptureResult = pid?.let { total.physicalCameraResults[it] } ?: total
            if (spec.keepRaw && o.raw) saveDng(o.chars, result, image, spec)
            return toRawFrame(image, result, o, spec)
        } catch (e: TimeoutCancellationException) {
            runCatching { o.session.abortCaptures() }
            throw IllegalStateException("La cámara no respondió a tiempo")
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { o.session.abortCaptures() }
            throw e
        } finally {
            image?.close()
            o.reader.setOnImageAvailableListener(null, null)
            // An image that arrived after a timeout or a cancel is released, never leaked.
            if (image == null && !imageReady.completeExceptionally(IllegalStateException("descartada"))) {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                runCatching { imageReady.getCompleted().close() }
            }
        }
    }

    private fun toRawFrame(image: Image, result: CaptureResult, o: Opened, spec: CaptureSpec): RawFrame {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val data = ShortArray(w * h)
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1e9 } ?: spec.exposureSec
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: spec.iso
        val meta = FrameMeta(exposure, iso, System.currentTimeMillis(), cameraId = o.info.id)
        if (!o.raw) {
            // YUV fallback: the luma plane (8 bit) as a monochrome frame.
            val row = ByteArray(rowStride)
            for (y in 0 until h) {
                buf.position(y * rowStride)
                buf.get(row, 0, minOf(rowStride, buf.remaining()))
                for (x in 0 until w) data[y * w + x] = (row[x].toInt() and 0xFF).toShort()
            }
            return RawFrame(w, h, data, Cfa.MONO, FloatArray(4), 255f, meta)
        }
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.asShortBuffer().get(data, y * w, w)
        }
        val ch = o.chars
        val black = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size == 4 }
            ?: ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                floatArrayOf(
                    p.getOffsetForIndex(0, 0).toFloat(), p.getOffsetForIndex(1, 0).toFloat(),
                    p.getOffsetForIndex(0, 1).toFloat(), p.getOffsetForIndex(1, 1).toFloat(),
                )
            } ?: FloatArray(4) { 64f }
        val white = (result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
        val cfa = if (o.info.mono) Cfa.MONO else when (ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> Cfa.RGGB
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> Cfa.GRBG
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> Cfa.GBRG
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> Cfa.BGGR
            else -> Cfa.MONO
        }
        return RawFrame(w, h, data, cfa, black.copyOf(), white, meta)
    }

    /** The untouched sensor data as DNG in Downloads/StarBridge/<session>/ (for Siril, DSS…). */
    private fun saveDng(ch: CameraCharacteristics, result: CaptureResult, image: Image, spec: CaptureSpec) {
        val name = "SB_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(java.util.Date()) + ".dng"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/StarBridge/" + spec.sessionName.ifEmpty { "RAW" })
        }
        val resolver = context.contentResolver
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { warning = "No se pudo guardar la foto en RAW (DNG): ¿almacenamiento lleno?"; return }
        try {
            resolver.openOutputStream(uri)?.use { out -> DngCreator(ch, result).use { it.writeImage(out, image) } }
                ?: error("sin acceso")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            warning = "No se pudo guardar la foto en RAW (DNG): ${e.message}"
        }
    }

    override suspend fun close() = lock.withLock { lastId = null; closeLocked() }

    private fun closeLocked() {
        val o = opened ?: return
        opened = null
        runCatching { o.session.close() }
        runCatching { o.device.close() }
        runCatching { o.reader.close() }
    }

    fun release() {
        runCatching { opened?.let { it.session.close(); it.device.close(); it.reader.close() } }
        opened = null
        thread.quitSafely()
    }
}
