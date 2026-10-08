package org.starbridge.core.sky

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.starbridge.core.mount.AltAz
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The part of the sky the user can really see (through a window, between trees…), drawn on the
 * map or traced with the telescope. Fixed to the ground: vertices in azimuth/altitude, joined by
 * great circles (the straight edges of a window frame). Several zones; the visible sky is the
 * union of the ones switched on.
 */
data class Zone(val id: String, val name: String, val on: Boolean, val points: List<AltAz>) {
    init {
        require(points.size in MIN_POINTS..MAX_POINTS) { "Una zona necesita de $MIN_POINTS a $MAX_POINTS puntos" }
        require(points.all { it.azDeg in 0.0..360.0 && it.altDeg in -10.0..90.0 }) { "Punto de la zona fuera de rango" }
        require(name.length <= MAX_NAME) { "Nombre demasiado largo" }
    }

    private val geometry by lazy { ZoneGeometry(points) }

    fun contains(p: AltAz): Boolean = geometry.contains(p)

    fun toJson() = buildJsonObject {
        put("id", id)
        put("name", name)
        put("on", on)
        put("points", buildJsonArray {
            points.forEach { add(buildJsonArray { add(JsonPrimitive(round2(it.azDeg))); add(JsonPrimitive(round2(it.altDeg))) }) }
        })
    }

    companion object {
        const val MIN_POINTS = 3
        const val MAX_POINTS = 64
        const val MAX_NAME = 40
        const val MAX_ZONES = 12

        private fun round2(x: Double) = Math.round(x * 100) / 100.0

        /** From a page's JSON; [id] is the one to use (the hub assigns new ones). */
        fun fromJson(o: JsonObject, id: String): Zone {
            val points = (o["points"] as? JsonArray ?: throw IllegalArgumentException("Faltan los puntos de la zona")).map {
                val pair = it.jsonArray
                AltAz(((pair[0].jsonPrimitive.double % 360) + 360) % 360, pair[1].jsonPrimitive.double)
            }
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { "Zona visible" }
            return Zone(id, name.take(MAX_NAME), o["on"]?.jsonPrimitive?.booleanOrNull ?: true, points)
        }
    }
}

/** All the zones, persisted as JSON. */
class Zones(val list: List<Zone> = emptyList()) {
    val active: List<Zone> get() = list.filter { it.on }

    /** Null when no zone is on (everything counts as visible); otherwise whether [p] is in one. */
    fun visible(p: AltAz): Boolean? = active.takeIf { it.isNotEmpty() }?.any { it.contains(p) }

    fun upsert(z: Zone): Zones {
        val replaced = list.any { it.id == z.id }
        require(replaced || list.size < Zone.MAX_ZONES) { "Como mucho ${Zone.MAX_ZONES} zonas" }
        return Zones(if (replaced) list.map { if (it.id == z.id) z else it } else list + z)
    }

    fun delete(id: String) = Zones(list.filter { it.id != id })

    fun newId(): String = "z" + ((list.mapNotNull { it.id.removePrefix("z").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    fun toJson() = JsonArray(list.map { it.toJson() })

    companion object {
        fun parse(text: String?): Zones = runCatching {
            Zones(kotlinx.serialization.json.Json.parseToJsonElement(text ?: return Zones()).jsonArray.map {
                val o = it.jsonObject
                Zone.fromJson(o, o["id"]?.jsonPrimitive?.contentOrNull ?: "z0")
            })
        }.getOrDefault(Zones())
    }
}

/**
 * Point-in-polygon on the sphere: the gnomonic projection on the plane tangent at the polygon's
 * centre turns great circles into straight lines, so the plane even-odd test is exact. Polygons
 * reaching 90° from their centre (a whole half-sky) fall back to a plain azimuth/altitude test.
 */
internal class ZoneGeometry(points: List<AltAz>) {
    private val vertices = points.map(::unit)
    private val centre = normalize(vertices.fold(DoubleArray(3)) { acc, v -> doubleArrayOf(acc[0] + v[0], acc[1] + v[1], acc[2] + v[2]) })
    // Any direction not parallel to the centre gives the tangent basis.
    private val e1 = normalize(cross(if (kotlin.math.abs(centre[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(0.0, 1.0, 0.0), centre))
    private val e2 = cross(centre, e1)
    private val projected = if (vertices.all { dot(it, centre) > MIN_COS }) vertices.map(::project) else null
    // Fallback: azimuths unwrapped around the centre's azimuth.
    private val centreAz = Math.toDegrees(kotlin.math.atan2(centre[0], centre[1]))
    private val flat = points.map { unwrap(it.azDeg, centreAz) to it.altDeg }

    fun contains(p: AltAz): Boolean {
        val poly = projected
        return if (poly != null) {
            val v = unit(p)
            if (dot(v, centre) <= MIN_COS) false else inside(project(v), poly)
        } else {
            inside(unwrap(p.azDeg, centreAz) to p.altDeg, flat)
        }
    }

    private fun project(v: DoubleArray): Pair<Double, Double> {
        val d = dot(v, centre)
        return dot(v, e1) / d to dot(v, e2) / d
    }

    companion object {
        private const val MIN_COS = 0.02

        fun unit(p: AltAz): DoubleArray {
            val a = Math.toRadians(p.azDeg)
            val h = Math.toRadians(p.altDeg)
            return doubleArrayOf(cos(h) * sin(a), cos(h) * cos(a), sin(h))
        }

        private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        private fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        private fun normalize(a: DoubleArray): DoubleArray {
            val l = sqrt(dot(a, a)).takeIf { it > 1e-12 } ?: return doubleArrayOf(0.0, 0.0, 1.0)
            return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }
        private fun unwrap(az: Double, around: Double): Double {
            var d = (az - around) % 360
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return around + d
        }

        /** Even-odd ray casting. */
        fun inside(p: Pair<Double, Double>, poly: List<Pair<Double, Double>>): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val (xi, yi) = poly[i]
                val (xj, yj) = poly[j]
                if ((yi > p.second) != (yj > p.second) && p.first < (xj - xi) * (p.second - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
