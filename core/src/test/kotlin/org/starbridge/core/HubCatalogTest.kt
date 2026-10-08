package org.starbridge.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.server.SessionHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The big catalogue through the hub: lists by aperture, exact matches first, the map by region. */
class HubCatalogTest {
    private val catalog = Catalog.loadDefault()
    private val t0 = 1_791_406_800_000L // 2026-10-07 21:00 UTC

    private fun last(received: List<String>, type: String): JsonObject =
        received.map { Json.parseToJsonElement(it).jsonObject }.last { it["type"]?.jsonPrimitive?.content == type }

    private fun ids(m: JsonObject) = m["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun listsFollowTheApertureAndExactMatchesComeFirst() = runTest {
        val hub = SessionHub(backgroundScope, catalog, clock = { t0 })
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }

        hub.onMessage("phone", """{"type":"search","q":"","category":"galaxy"}""")
        val small = ids(last(received, "searchResult"))
        // EAA lists just for one request (the iPhone stacking app): fainter, the setting untouched.
        hub.onMessage("phone", """{"type":"search","q":"","category":"galaxy","eaa":true}""")
        val forStacking = ids(last(received, "searchResult"))
        assertTrue(forStacking != small, "EAA lists reach fainter objects")
        hub.onMessage("phone", """{"type":"search","q":"","category":"galaxy"}""")
        assertEquals(small, ids(last(received, "searchResult")), "the shared setting did not change")
        hub.onMessage("phone", """{"type":"setSetting","key":"apertureMm","value":"279"}""")
        hub.onMessage("phone", """{"type":"setSetting","key":"listsEaa","value":"true"}""")
        val limit = last(received, "info")["settings"]!!.jsonObject["listLimit"]!!.jsonPrimitive.double
        assertTrue(limit > 14, "279 mm + EAA → $limit")
        hub.onMessage("phone", """{"type":"search","q":"","category":"galaxy"}""")
        val big = ids(last(received, "searchResult"))
        assertTrue(small.isNotEmpty() && big.isNotEmpty())

        // Typed exactly: first, even if it is below the horizon or very faint.
        hub.onMessage("phone", """{"type":"search","q":"NGC 2","category":"galaxy"}""")
        assertEquals("NGC 2", ids(last(received, "searchResult")).first())
        hub.onMessage("phone", """{"type":"search","q":"pgc 2557","category":"galaxy"}""")
        assertEquals("M31", ids(last(received, "searchResult")).first())
    }

    @Test
    fun theMapGetsTheFaintOnesOnlyByRegion() = runTest {
        val hub = SessionHub(backgroundScope, catalog, clock = { t0 })
        val received = mutableListOf<String>()
        hub.onConnect("phone") { received += it }

        hub.onMessage("phone", """{"type":"sky"}""")
        val sky = last(received, "sky")
        val wholeSky = sky["stars"]!!.jsonArray.size
        assertTrue(wholeSky in 500..2500, "whole sky to mag 5: $wholeSky stars")

        hub.onMessage("phone", """{"type":"skyRegion","az":0,"alt":60,"radius":10,"starMag":8,"dsoMag":13}""")
        val region = last(received, "skyRegion")
        val stars = region["stars"]!!.jsonArray
        assertTrue(stars.size > 150, "a 10° circle to mag 8: ${stars.size} stars")
        assertTrue(stars.all { (it.jsonArray[2].jsonPrimitive.double) <= 8.0 })
        // Everything returned is near the requested point.
        val c = org.starbridge.core.pointing.PointingParams
        stars.forEach { s ->
            val sep = c.separation(org.starbridge.core.mount.AltAz(s.jsonArray[0].jsonPrimitive.double, s.jsonArray[1].jsonPrimitive.double), org.starbridge.core.mount.AltAz(0.0, 60.0))
            assertTrue(sep < 12.0, "star $sep° away")
        }
    }
}
