package org.starbridge.desktop

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI

/**
 * The web port of the PC program: 8080, unless another program uses it (a web server, another
 * app…), then the next free one up to 8099, remembered for next time. If StarBridge itself is
 * already running there, [alreadyRunning] is called with its port instead.
 */
object WebPort {
    private const val KEY = "httpPort"

    fun choose(explicit: Int?, settings: FileSettings, alreadyRunning: (Int) -> Unit): Int {
        val key = settings.get(org.starbridge.core.server.SessionHub.KEY_ACCESS)
        val candidates = if (explicit != null) listOf(explicit)
        else (listOfNotNull(settings.get(KEY)?.toIntOrNull()) + (8080..8099)).distinct()
        for (port in candidates) {
            if (isStarBridge(port, key)) { alreadyRunning(port); return port }
            if (isFree(port)) {
                if (explicit == null) settings.put(KEY, port.toString())
                return port
            }
        }
        return explicit ?: 8080 // nothing free: the server start will say so
    }

    private fun isFree(port: Int): Boolean = runCatching {
        ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress("0.0.0.0", port)) }
    }.isSuccess

    /** Our own server answers /diag.txt with our key; anything else is another program. */
    private fun isStarBridge(port: Int, key: String?): Boolean {
        if (key == null) return false
        return runCatching {
            val c = URI("http://127.0.0.1:$port/diag.txt?k=$key").toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 600
            c.readTimeout = 1500
            try {
                c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() }.contains("StarBridge")
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }
}
