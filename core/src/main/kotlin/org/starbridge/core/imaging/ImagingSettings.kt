package org.starbridge.core.imaging

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.starbridge.imaging.DenoiseLevel
import org.starbridge.imaging.QualityMode
import org.starbridge.imaging.RenderSettings

/**
 * Imaging settings of one camera. Everything starts in Auto; each setting has a manual value.
 * Saved per camera.
 */
data class ImagingSettings(
    val exposureAuto: Boolean = true,
    val exposureSec: Double = 10.0,
    val isoAuto: Boolean = true,
    val iso: Int = 800,
    /** 0 = auto (from the sensor size), else 1…4. */
    val binning: Int = 0,
    /** Colour stack for Bayer sensors (mono sensors are always mono). */
    val color: Boolean = true,
    val quality: QualityMode = QualityMode.CONSERVATIVE,
    val denoise: DenoiseLevel = DenoiseLevel.AUTO,
    /** Auto-stretch target for the background (0.05…0.6). */
    val background: Double = 0.25,
    /** Manual black point shift (−1…1) and contrast (0.5…2) on top of the auto stretch. */
    val blackShift: Double = 0.0,
    val contrast: Double = 1.0,
    val removeGradient: Boolean = true,
    val focusAuto: Boolean = true,
    /** Lens focus in diopters (0 = infinity), used when [focusAuto] is off. */
    val focusDiopters: Double = 0.0,
    /** Also keep every sub-frame as RAW (DNG) for processing elsewhere. */
    val keepRaw: Boolean = false,
) {
    fun render(maxSize: Int) = RenderSettings(
        targetBackground = background, blackShift = blackShift, contrast = contrast,
        removeGradient = removeGradient, denoise = denoise, nightRed = false, maxSize = maxSize,
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("exposureAuto", exposureAuto)
        put("exposureSec", exposureSec)
        put("isoAuto", isoAuto)
        put("iso", iso)
        put("binning", binning)
        put("color", color)
        put("quality", quality.name.lowercase())
        put("denoise", denoise.name.lowercase())
        put("background", background)
        put("blackShift", blackShift)
        put("contrast", contrast)
        put("removeGradient", removeGradient)
        put("focusAuto", focusAuto)
        put("focusDiopters", focusDiopters)
        put("keepRaw", keepRaw)
    }

    /** Applies the fields present in [o] (partial updates from the web), validated. */
    fun merge(o: JsonObject): ImagingSettings {
        fun b(k: String) = o[k]?.jsonPrimitive?.booleanOrNull
        fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        fun i(k: String) = o[k]?.jsonPrimitive?.intOrNull
        fun s(k: String) = o[k]?.jsonPrimitive?.content
        return copy(
            exposureAuto = b("exposureAuto") ?: exposureAuto,
            exposureSec = d("exposureSec")?.also { require(it > 0 && it <= 3600) { "Exposición no válida" } } ?: exposureSec,
            isoAuto = b("isoAuto") ?: isoAuto,
            iso = i("iso")?.also { require(it in 10..409_600) { "ISO no válido" } } ?: iso,
            binning = i("binning")?.also { require(it in 0..4) { "Agrupado no válido" } } ?: binning,
            color = b("color") ?: color,
            quality = s("quality")?.let { q -> QualityMode.entries.firstOrNull { it.name.equals(q, true) } ?: error("Filtro no válido") } ?: quality,
            denoise = s("denoise")?.let { q -> DenoiseLevel.entries.firstOrNull { it.name.equals(q, true) } ?: error("Suavizado no válido") } ?: denoise,
            background = d("background")?.coerceIn(0.05, 0.6) ?: background,
            blackShift = d("blackShift")?.coerceIn(-1.0, 1.0) ?: blackShift,
            contrast = d("contrast")?.coerceIn(0.5, 2.0) ?: contrast,
            removeGradient = b("removeGradient") ?: removeGradient,
            focusAuto = b("focusAuto") ?: focusAuto,
            focusDiopters = d("focusDiopters")?.coerceIn(0.0, 20.0) ?: focusDiopters,
            keepRaw = b("keepRaw") ?: keepRaw,
        )
    }

    /** True if changing from [other] to this needs a new stack (geometry or calibration change). */
    fun needsNewStack(other: ImagingSettings): Boolean =
        binning != other.binning || color != other.color || exposureSec != other.exposureSec ||
            iso != other.iso || exposureAuto != other.exposureAuto || isoAuto != other.isoAuto ||
            focusDiopters != other.focusDiopters || focusAuto != other.focusAuto

    /** Only the look of the picture changed: re-render, no new frames needed. */
    fun renderChanged(other: ImagingSettings): Boolean =
        denoise != other.denoise || background != other.background || blackShift != other.blackShift ||
            contrast != other.contrast || removeGradient != other.removeGradient

    companion object {
        fun parse(text: String?): ImagingSettings = runCatching {
            if (text == null) return ImagingSettings()
            ImagingSettings().merge(kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject)
        }.getOrDefault(ImagingSettings())
    }
}
