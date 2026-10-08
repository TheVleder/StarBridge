package org.starbridge.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/** One StarBridge brain (the program on a PC or the Android app) as announced on the WiFi. */
data class BrainInfo(
    /** Random, stable per installation: tells two brains apart (and a brain from itself). */
    val id: String,
    /** "pc" or "android". */
    val kind: String,
    /** Human name, e.g. "PC · SALON" or "Android · CLT-L29". */
    val name: String,
    val httpPort: Int,
    /** The telescope cable is connected to this brain and the hand control answers. */
    val telescope: Boolean,
    val model: String? = null,
    /** Address to reach it, when not the sender's (a dev server listening on 127.0.0.1 only). */
    val host: String? = null,
)

/** A brain seen on the network. */
data class Peer(val info: BrainInfo, val host: String, val lastSeenMs: Long) {
    val baseUrl: String get() = "http://${info.host ?: host}:${info.httpPort}"
}

/**
 * LAN discovery without any app or server: every brain broadcasts a small UDP datagram every
 * [intervalMs] and listens for the others. The datagram never carries the access key: a device
 * that finds a brain still needs its code (QR or typed) to control it.
 *
 * Datagram (UTF-8 JSON, UDP port [PORT]):
 *   {"starbridge":1,"id":"…","kind":"pc|android","name":"…","http":8080,"telescope":true,"model":"…","host":"…"}
 * ("model" and "host" are optional; without "host" the brain is reached at the sender's address.)
 */
class Beacon(
    private val scope: CoroutineScope,
    private val self: () -> BrainInfo,
    private val onPeers: (List<Peer>) -> Unit,
    private val port: Int = PORT,
    private val intervalMs: Long = 2_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = ConcurrentHashMap<String, Peer>()
    private var jobs: List<Job> = emptyList()
    @Volatile private var lastSignature = ""

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(scope.launch(Dispatchers.IO) { sendLoop() }, scope.launch(Dispatchers.IO) { receiveLoop() })
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** Brains currently seen (others, never this one). */
    fun peers(): List<Peer> = seen.values.sortedBy { it.info.name }

    private suspend fun sendLoop() {
        val socket = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return
        socket.use {
            while (scope.isActive) {
                val bytes = encode(self()).toByteArray(Charsets.UTF_8)
                for (target in targets()) {
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, port)) }
                }
                expire()
                delay(intervalMs)
            }
        }
    }

    private fun receiveLoop() {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true // several brains (or a brain and a test) on one computer
                broadcast = true
                soTimeout = 1_000
                bind(InetSocketAddress(this@Beacon.port)) // (inside apply, `port` is the socket's)
            }
        }.getOrNull() ?: return
        socket.use {
            val buf = ByteArray(2048)
            while (scope.isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (!scope.isActive) break else continue
                }
                val info = decode(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                if (info.id == self().id) continue
                val addr = packet.address ?: continue
                // The same brain may arrive by the LAN and by the loopback: keep the LAN address.
                val old = seen[info.id]
                val host = if (addr.isLoopbackAddress && old != null && !InetAddress.getByName(old.host).isLoopbackAddress) old.host
                else addr.hostAddress ?: continue
                seen[info.id] = Peer(info, host, clock())
                publishIfChanged()
            }
        }
    }

    private fun expire() {
        val now = clock()
        seen.entries.removeIf { now - it.value.lastSeenMs > intervalMs * 3 + 1_000 }
        publishIfChanged()
    }

    private fun publishIfChanged() {
        val list = peers()
        val sig = list.joinToString("|") { "${it.info.id}@${it.host}:${it.info.httpPort}:${it.info.telescope}:${it.info.name}" }
        if (sig != lastSignature) {
            lastSignature = sig
            runCatching { onPeers(list) }
        }
    }

    /** Every IPv4 broadcast address of the interfaces that are up, plus the loopback (same computer). */
    private fun targets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp) continue
                for (a in nif.interfaceAddresses) {
                    if (a.address is Inet4Address) a.broadcast?.let { out += it }
                }
            }
        }
        runCatching { out += InetAddress.getByName("255.255.255.255") }
        runCatching { out += InetAddress.getByName("127.0.0.1") }
        return out.toList()
    }

    companion object {
        const val PORT = 47_821

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(i: BrainInfo): String = buildJsonObject {
            put("starbridge", 1)
            put("id", i.id)
            put("kind", i.kind)
            put("name", i.name)
            put("http", i.httpPort)
            put("telescope", i.telescope)
            i.model?.let { put("model", it) }
            i.host?.let { put("host", it) }
        }.toString()

        fun decode(text: String): BrainInfo? = runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            if (o["starbridge"]?.jsonPrimitive?.intOrNull != 1) return null
            val kind = o["kind"]?.jsonPrimitive?.content ?: return null
            val port = o["http"]?.jsonPrimitive?.intOrNull ?: return null
            if (port !in 1..65535) return null
            BrainInfo(
                id = o["id"]?.jsonPrimitive?.content?.take(64) ?: return null,
                kind = kind.take(16),
                name = (o["name"]?.jsonPrimitive?.content ?: kind).take(60),
                httpPort = port,
                telescope = o["telescope"]?.jsonPrimitive?.booleanOrNull ?: false,
                model = o["model"]?.jsonPrimitive?.content?.take(40),
                host = o["host"]?.jsonPrimitive?.content?.take(64)?.takeIf { h -> h.matches(Regex("[0-9.]{7,15}")) },
            )
        }.getOrNull()

        /** A stable id for this installation, kept in the settings. */
        fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
