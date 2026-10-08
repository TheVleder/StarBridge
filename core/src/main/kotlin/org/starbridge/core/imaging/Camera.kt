package org.starbridge.core.imaging

import org.starbridge.imaging.Picture
import org.starbridge.imaging.RawFrame
import java.io.File

/** What a camera can do, as measured from its characteristics (Camera2 on Android). */
data class CameraInfo(
    /** Camera id; physical cameras of a logical multi-camera are "logical/physical". */
    val id: String,
    /** Human label, e.g. "Trasera · monocromo 20 MP". */
    val label: String,
    /** back, front or external. */
    val facing: String,
    /** Delivers RAW_SENSOR data (otherwise the luma of YUV frames is used). */
    val raw: Boolean,
    /** Exposure and ISO can be set by hand. */
    val manual: Boolean,
    val mono: Boolean,
    /** Size of the frames delivered (RAW or YUV). */
    val width: Int,
    val height: Int,
    val minExposureSec: Double,
    val maxExposureSec: Double,
    val minIso: Int,
    val maxIso: Int,
    /** Highest ISO done with analog gain (best read noise); null if unknown. */
    val maxAnalogIso: Int? = null,
    /** The lens focus can be moved (0 diopters = infinity … [minFocusDiopters]). */
    val focusable: Boolean = false,
    val minFocusDiopters: Double = 0.0,
    val focalLengthMm: Double? = null,
    /** Short notes shown in the camera picker ("sin RAW", "máx. 1 s", …). */
    val notes: List<String> = emptyList(),
) {
    val megapixels: Double get() = width * height / 1e6

    /** Suitability for EAA: RAW, manual long exposures, monochrome, back camera. */
    val score: Double get() =
        (if (raw) 100.0 else 0.0) + (if (manual) 60.0 else 0.0) + (if (mono) 40.0 else 0.0) +
            (if (facing == "back") 30.0 else 0.0) + 8.0 * kotlin.math.ln(1.0 + maxExposureSec) +
            (if (id.contains('/')) -3.0 else 0.0)
}

/** One exposure. [focusDiopters] 0 = infinity. */
data class CaptureSpec(
    val exposureSec: Double,
    val iso: Int,
    val focusDiopters: Double = 0.0,
    /** Also keep this sub-frame as a RAW file (DNG on Android) for processing elsewhere. */
    val keepRaw: Boolean = false,
    /** Session name: groups the kept RAW files in one folder. */
    val sessionName: String = "",
)

/** The phone camera (Camera2 on Android, a simulated sky on the PC dev server). */
interface CameraSource {
    /** Every usable camera, physical cameras of logical multi-cameras included. */
    fun cameras(): List<CameraInfo>

    /** Opens [id] (closing any other); returns its capabilities. */
    suspend fun open(id: String): CameraInfo

    /** One exposure. Cancelling the coroutine aborts the exposure. */
    suspend fun capture(spec: CaptureSpec): RawFrame

    suspend fun close()

    /** A problem the user should know about that did not stop the capture (e.g. a DNG not saved). */
    fun takeWarning(): String? = null
}

/** Platform services the imaging session needs. */
interface ImagingHost {
    /** Encodes an 8-bit picture as JPEG. */
    fun jpeg(picture: Picture, quality: Int): ByteArray

    /** Android thermal status 0 (none) … 6 (shutdown); −1 unknown. */
    fun thermalStatus(): Int = -1

    /** Battery percent and charging state; null if unknown. */
    fun battery(): Pair<Int, Boolean>? = null

    /**
     * Makes an exported file visible to the user (Android: copied to Downloads/StarBridge).
     * Returns where it is, for the message shown to the user.
     */
    fun publish(file: File, mime: String): String = file.absolutePath
}

/** RMS angular rate of the phone over a time window (gyroscope). */
interface GyroWindow {
    fun begin()

    /** RMS deg/s since [begin], null if no samples. */
    fun end(): Double?
}

/** What the imaging session needs to know about the mount. */
interface MountLink {
    fun connected(): Boolean

    /** GoTo, manual slew or calibration running (tracking does not count). */
    fun moving(): Boolean

    fun gotoActive(): Boolean

    /** Last known sky pointing (azimuth, altitude) in degrees. */
    fun pointing(): Pair<Double, Double>?

    fun latitude(): Double?

    /** Name of the object being tracked, if any. */
    fun target(): String?

    companion object {
        val NONE = object : MountLink {
            override fun connected() = false
            override fun moving() = false
            override fun gotoActive() = false
            override fun pointing(): Pair<Double, Double>? = null
            override fun latitude(): Double? = null
            override fun target(): String? = null
        }
    }
}

/** A file served over HTTP. */
class HttpFile(val bytes: ByteArray, val contentType: String, val downloadName: String? = null)
