package org.starbridge.core.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pictures sent by other cameras on the network (the iPhone app StarBridge EAA), so every page —
 * the phone web, the PC panel — can see them:
 * - a **live** picture per source: the stack being built, replaced on every upload (id `live-<source>`);
 * - **saved** pictures: one per "Guardar" on the camera, the last [maxSaved] kept (and at most
 *   [maxBytes] in total).
 * In memory only: they are a view of the night, the camera keeps the originals.
 */
class PhotoStore(private val maxSaved: Int = 40, private val maxBytes: Long = 150L * 1024 * 1024) {
    data class Photo(
        val id: String,
        val v: Long,
        val at: Long,
        val source: String,
        val live: Boolean,
        val label: String?,
        val objectId: String?,
        val w: Int?,
        val h: Int?,
        val frames: Int?,
        val exposureS: Double?,
        val integrationS: Double?,
        val state: String?,
        val jpeg: ByteArray,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("v", v)
            put("at", at)
            put("source", source)
            put("live", live)
            label?.let { put("label", it) }
            objectId?.let { put("objectId", it) }
            w?.let { put("w", it) }
            h?.let { put("h", it) }
            frames?.let { put("frames", it) }
            exposureS?.let { put("exposureS", it) }
            integrationS?.let { put("integrationS", it) }
            state?.let { put("state", it) }
        }

        override fun equals(other: Any?) = other is Photo && other.id == id && other.v == v
        override fun hashCode() = 31 * id.hashCode() + v.hashCode()
    }

    private val live = LinkedHashMap<String, Photo>()
    private val saved = ArrayDeque<Photo>()
    private var version = 0L
    private var nextSaved = 1

    /**
     * Stores one upload. [params] are the query parameters of `POST /photos` (all optional):
     * source, live, label, objectId, w, h, frames, exposureS, integrationS, takenAt, state.
     */
    @Synchronized
    fun put(params: Map<String, String>, jpeg: ByteArray, now: Long): Photo {
        val source = params["source"]?.takeIf { it.isSafeId() } ?: "phone"
        val isLive = params["live"].let { it == "1" || it == "true" }
        version++
        val photo = Photo(
            id = if (isLive) "live-$source" else "p${nextSaved++}",
            v = version,
            at = params["takenAt"]?.toLongOrNull() ?: now,
            source = source,
            live = isLive,
            label = params["label"]?.take(80)?.takeIf { it.isNotBlank() },
            objectId = params["objectId"]?.take(40)?.takeIf { it.isNotBlank() },
            w = params["w"]?.toIntOrNull(),
            h = params["h"]?.toIntOrNull(),
            frames = params["frames"]?.toIntOrNull(),
            exposureS = params["exposureS"]?.toDoubleOrNull(),
            integrationS = params["integrationS"]?.toDoubleOrNull(),
            state = params["state"]?.take(20)?.takeIf { it.isNotBlank() },
            jpeg = jpeg,
        )
        if (isLive) {
            live[source] = photo
        } else {
            saved.addLast(photo)
            while (saved.size > maxSaved || (saved.size > 1 && saved.sumOf { it.jpeg.size.toLong() } > maxBytes)) saved.removeFirst()
        }
        return photo
    }

    /** By id, with or without the ".jpg". */
    @Synchronized
    fun get(id: String): Photo? {
        val key = id.removeSuffix(".jpg")
        return live.values.firstOrNull { it.id == key } ?: saved.firstOrNull { it.id == key }
    }

    /** Live pictures first, then the saved ones, newest first. */
    @Synchronized
    fun list(): List<Photo> = live.values.sortedByDescending { it.at } + saved.reversed()

    private fun String.isSafeId() = isNotEmpty() && length <= 24 && all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    }
}
