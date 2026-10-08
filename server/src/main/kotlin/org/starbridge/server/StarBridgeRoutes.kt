package org.starbridge.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starbridge.core.server.PhotoStore
import org.starbridge.core.server.SessionHub
import org.starbridge.core.share.QrCode
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Everything the browser talks to, shared by the Android app and the devserver:
 * - `/ws`: JSON frames to the [SessionHub] (needs `?k=KEY`);
 * - `/qr.svg`: the share QR (needs the key); `/qr-app.svg`: the same for the iPhone app;
 * - `/img/...`: live stack pictures and exported files (need the key);
 * - `/diag.txt`: diagnostics report (needs the key);
 * - `/photos`: pictures from other cameras on the network, e.g. the iPhone app (POST a JPEG,
 *   GET `/photos/{id}.jpg`; both need the key — see [PhotoStore]);
 * - the web UI itself, which is public: it holds no secrets.
 *
 * [baseUrl] is the LAN address put in the QR (null without WiFi); [staticFile] returns the
 * bytes of a file of the web UI (`index.html`, `app.js`…) or null if there is none.
 */
fun Application.starBridge(hub: SessionHub, baseUrl: () -> String?, staticFile: (String) -> ByteArray?) {
    install(WebSockets) {
        pingPeriod = 5.seconds
        timeout = 15.seconds
    }
    routing {
        webSocket("/ws") {
            if (!hub.checkKey(call.request.queryParameters["k"])) {
                close(CloseReason(SessionHub.CLOSE_UNAUTHORIZED, "Acceso no autorizado"))
                return@webSocket
            }
            val id = UUID.randomUUID().toString().take(8)
            // Never block the hub on a slow client: drop frames if its buffer is full.
            hub.onConnect(
                id,
                send = { text -> outgoing.trySend(Frame.Text(text)) },
                close = { close(CloseReason(SessionHub.CLOSE_UNAUTHORIZED, "Clave cambiada")) },
            )
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    // STOP overtakes anything this client is still waiting for.
                    if (hub.isUrgent(text)) launch { hub.onMessage(id, text) } else hub.onMessage(id, text)
                }
            } finally {
                // Must run even if this coroutine is being cancelled: it stops the mount.
                withContext(NonCancellable) { hub.onDisconnect(id) }
            }
        }
        get("/qr.svg") {
            val base = baseUrl()
            if (!hub.checkKey(call.request.queryParameters["k"]) || base == null) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }
            call.respondText(QrCode.svg(hub.shareUrl(base)), ContentType.Image.SVG)
        }
        // The same link for the iPhone app StarBridge EAA (its own URL scheme).
        get("/qr-app.svg") {
            val base = baseUrl()
            if (!hub.checkKey(call.request.queryParameters["k"]) || base == null) {
                return@get call.respond(HttpStatusCode.Forbidden)
            }
            call.respondText(QrCode.svg(APP_SCHEME + hub.shareUrl(base).substringAfter("://")), ContentType.Image.SVG)
        }
        // State, events and serial traffic as plain text, to share when something misbehaves.
        get("/diag.txt") {
            if (!hub.checkKey(call.request.queryParameters["k"])) return@get call.respond(HttpStatusCode.Forbidden)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(hub.diagnosticsText(), ContentType.Text.Plain)
        }
        get("/img/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
            val f = hub.imagingHttp(path, call.request.queryParameters["k"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            f.downloadName?.let {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, it).toString(),
                )
            }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(f.bytes, ContentType.parse(f.contentType))
        }
        // Another camera (the iPhone app) shares a picture: JPEG body, metadata in the query.
        post("/photos") {
            if (!hub.checkKey(call.request.queryParameters["k"])) return@post call.respond(HttpStatusCode.Forbidden)
            val bytes = call.receiveChannel().readRemaining(PhotoStore.MAX_UPLOAD_BYTES + 1L).readByteArray()
            if (bytes.isEmpty() || bytes.size > PhotoStore.MAX_UPLOAD_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            // JPEG only: it is shown as is by every page
            if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
                return@post call.respond(HttpStatusCode.UnsupportedMediaType)
            }
            val params = call.request.queryParameters.entries().associate { (k, v) -> k to v.firstOrNull().orEmpty() } - "k"
            val p = hub.addPhoto(params, bytes)
            call.respondText("{\"id\":\"${p.id}\",\"v\":${p.v}}", ContentType.Application.Json)
        }
        get("/photos/{name}") {
            val p = hub.photoHttp(call.parameters["name"].orEmpty(), call.request.queryParameters["k"])
                ?: return@get call.respond(HttpStatusCode.NotFound)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondBytes(p.jpeg, ContentType.Image.JPEG)
        }
        get("/") { serveStatic(call, "index.html", staticFile) }
        get("/{file}") { serveStatic(call, call.parameters["file"].orEmpty(), staticFile) }
    }
}

/** URL scheme of the iPhone app StarBridge EAA: starbridge-eaa://IP:PORT/?k=KEY */
const val APP_SCHEME = "starbridge-eaa://"

private suspend fun serveStatic(call: ApplicationCall, name: String, staticFile: (String) -> ByteArray?) {
    if (!isSafeName(name)) return call.respond(HttpStatusCode.NotFound)
    val bytes = staticFile(name) ?: return call.respond(HttpStatusCode.NotFound)
    // A new APK brings a new web UI: Safari (and the home-screen app) must revalidate.
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondBytes(bytes, contentTypeOf(name))
}

/** Only plain file names of the web folder: no paths, no parent directories. */
internal fun isSafeName(name: String) =
    name.isNotEmpty() && !name.contains("..") && !name.contains('/') && !name.contains('\\')

internal fun contentTypeOf(name: String): ContentType = when (name.substringAfterLast('.', "").lowercase()) {
    "html" -> ContentType.Text.Html
    "js" -> ContentType.Application.JavaScript
    "css" -> ContentType.Text.CSS
    "svg" -> ContentType.Image.SVG
    "json" -> ContentType.Application.Json
    "png" -> ContentType.Image.PNG
    "webmanifest" -> ContentType("application", "manifest+json")
    else -> ContentType.Application.OctetStream
}
