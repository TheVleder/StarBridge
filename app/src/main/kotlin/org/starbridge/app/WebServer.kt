package org.starbridge.app

import android.content.res.AssetManager
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import org.starbridge.core.server.SessionHub
import org.starbridge.server.starBridge

/**
 * HTTP (web UI from assets/web) + WebSocket (/ws) on the local network. The routes are shared
 * with the devserver (`:server` module); here they only get the Android assets.
 */
class WebServer(
    private val assets: AssetManager,
    private val hub: SessionHub,
    /** LAN base URL (e.g. http://172.20.10.3:8080), null without WiFi. */
    private val baseUrl: () -> String?,
    private val port: Int = PORT,
) {
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            // The phone page (web/) and the computer's panel (desk/, from the desktop module): a PC
            // browser gets the same panel whether the telescope is on the PC or on this Android.
            starBridge(hub, baseUrl) { name ->
                runCatching { assets.open("web/$name").use { it.readBytes() } }.getOrNull()
                    ?: runCatching { assets.open("desk/$name").use { it.readBytes() } }.getOrNull()
            }
        }.start(wait = false)
    }

    /** Short grace period: it runs while the service is being destroyed. */
    fun stop() {
        server?.stop(100, 300)
        server = null
    }

    companion object {
        const val PORT = 8080
    }
}
