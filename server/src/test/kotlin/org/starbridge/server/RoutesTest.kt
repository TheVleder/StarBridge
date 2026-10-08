package org.starbridge.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.server.SessionHub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutesTest {
    private val hub = SessionHub(CoroutineScope(SupervisorJob() + Dispatchers.Default), Catalog.loadDefault())
    private val files = mapOf(
        "index.html" to "<html>StarBridge</html>",
        "app.js" to "console.log(1)",
        "manifest.webmanifest" to "{}",
    )

    private fun app(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { starBridge(hub, { "http://172.20.10.3:8080" }) { files[it]?.toByteArray() } }
        block()
    }

    @Test
    fun servesTheWebUiWithoutCaching() = app {
        val index = client.get("/")
        assertEquals(HttpStatusCode.OK, index.status)
        assertEquals("<html>StarBridge</html>", index.bodyAsText())
        assertEquals("no-cache", index.headers[HttpHeaders.CacheControl])
        assertTrue(index.contentType()!!.match(ContentType.Text.Html))
        assertTrue(client.get("/app.js").contentType()!!.match(ContentType.Application.JavaScript))
        assertEquals("application/manifest+json", client.get("/manifest.webmanifest").contentType()!!.withoutParameters().toString())
    }

    @Test
    fun unknownAndUnsafeNamesAreNotFound() = app {
        assertEquals(HttpStatusCode.NotFound, client.get("/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/..%2Fsecret").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/img/view.jpg").status) // no key
    }

    @Test
    fun photosFromAnotherCameraAreSharedWithTheKey() = app {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(HttpStatusCode.Forbidden, client.post("/photos") { setBody(jpeg) }.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, client.post("/photos?k=${hub.accessKey}") { setBody("not a jpeg") }.status)
        // the running stack: replaced on every upload, same id
        val live1 = client.post("/photos?k=${hub.accessKey}&source=iphone&live=1&label=M42&frames=12") { setBody(jpeg) }
        assertEquals(HttpStatusCode.OK, live1.status)
        assertTrue(live1.bodyAsText().contains("\"id\":\"live-iphone\""))
        client.post("/photos?k=${hub.accessKey}&source=iphone&live=1&label=M42&frames=20") { setBody(jpeg) }
        // a saved picture: a new id each time
        val saved = client.post("/photos?k=${hub.accessKey}&source=iphone&label=M42") { setBody(jpeg) }
        assertTrue(saved.bodyAsText().contains("\"id\":\"p1\""))
        assertEquals(listOf("live-iphone", "p1"), hub.photos.list().map { it.id })
        assertEquals(20, hub.photos.get("live-iphone")?.frames)
        assertEquals(HttpStatusCode.NotFound, client.get("/photos/live-iphone.jpg").status) // no key
        val got = client.get("/photos/live-iphone.jpg?k=${hub.accessKey}")
        assertEquals(HttpStatusCode.OK, got.status)
        assertTrue(got.contentType()!!.match(ContentType.Image.JPEG))
        assertTrue(got.readRawBytes().contentEquals(jpeg))
    }

    @Test
    fun qrNeedsTheKey() = app {
        assertEquals(HttpStatusCode.Forbidden, client.get("/qr.svg").status)
        val ok = client.get("/qr.svg?k=${hub.accessKey}")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().startsWith("<svg"))
    }

    @Test
    fun diagnosticsNeedTheKey() = app {
        assertEquals(HttpStatusCode.Forbidden, client.get("/diag.txt").status)
        val ok = client.get("/diag.txt?k=${hub.accessKey}")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("diagnóstico"))
    }

    @Test
    fun websocketRejectsAWrongKeyAndGreetsTheRightOne() = app {
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws?k=wrong") {
            val reason = closeReason.await()
            assertEquals(SessionHub.CLOSE_UNAUTHORIZED, reason?.code)
        }
        ws.webSocket("/ws?k=${hub.accessKey}") {
            val first = incoming.receive() as Frame.Text
            assertTrue(first.readText().contains("\"type\":\"info\""))
        }
    }
}
