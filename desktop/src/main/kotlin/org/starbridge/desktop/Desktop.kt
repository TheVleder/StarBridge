package org.starbridge.desktop

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.server.SessionHub
import org.starbridge.core.share.LanAddress
import org.starbridge.core.share.QrCode
import org.starbridge.server.starBridge
import java.io.File
import org.starbridge.core.net.Beacon
import org.starbridge.core.net.BrainInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * StarBridge on a computer: the brain that normally runs on the Android, talking to the hand
 * control through the USB-serial cable (a COM port) and serving the web UI on the computer's
 * WiFi. No GPS (the site is set once in Ajustes → Lugar; the time comes from the computer),
 * no phone sensors and no camera.
 *
 * The computer gets its own control panel (`desk/`, opened in an app window) with everything
 * the phones have and more; the iPhone keeps using the phone page (`web/`).
 *
 *   gradlew :desktop:run [--args="--port COM3 --http 8080 --ip 172.20.10.4 --no-window"]
 *
 * Console: q = stop the telescope and quit, v = open the windows again, qr = connection page,
 * d = diagnostics
 */
fun main(args: Array<String>) {
    val opts = Options.parse(args)
    val home = File(System.getProperty("user.home"), ".starbridge").apply { mkdirs() }
    val settings = FileSettings(File(home, "settings.properties"))
    // Already running (double-clicked twice): just show its panel. Port 8080 taken by another
    // program (a web server…): the next free one, remembered for next time.
    val httpPort = WebPort.choose(opts.httpPort, settings) { running ->
        log("StarBridge ya está abierto: mostrando su panel")
        if (!opts.noWindow) AppWindow.open("http://localhost:$running/desk.html?k=${settings.get(SessionHub.KEY_ACCESS).orEmpty()}")
        kotlin.system.exitProcess(0)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hub = SessionHub(scope, Catalog.loadDefault(), settings = settings)
    val lan = { (opts.ip ?: lanIp())?.let { "http://$it:$httpPort" } }

    val loader = Thread.currentThread().contextClassLoader
    fun resource(path: String) = loader.getResourceAsStream(path)?.use { it.readBytes() }
    embeddedServer(CIO, port = httpPort, host = "0.0.0.0") {
        // The phone page and the computer's panel side by side (no file has the same name).
        starBridge(hub, lan) { name -> resource("web/$name") ?: resource("desk/$name") }
        routing {
            // The section windows the user wants opened with the program ("photos,control").
            get("/desk-layout") {
                if (!hub.checkKey(call.request.queryParameters["k"])) return@get call.respond(HttpStatusCode.Forbidden)
                call.respondText(windowLayout(settings).joinToString(","), ContentType.Text.Plain)
            }
            post("/desk-layout") {
                if (!hub.checkKey(call.request.queryParameters["k"])) return@post call.respond(HttpStatusCode.Forbidden)
                val list = call.receiveText().take(500).split(',').map { it.trim() }.filter { it in SECTION_WINDOWS }.distinct()
                settings.put(KEY_LAYOUT, list.joinToString(","))
                call.respondText(list.joinToString(","), ContentType.Text.Plain)
            }
        }
    }.start(wait = false)
    // Tell the other brains (an Android with the telescope…) we are here, and find them.
    hub.brainKind = "pc"
    val brainId = settings.get(KEY_BRAIN_ID) ?: Beacon.newId().also { settings.put(KEY_BRAIN_ID, it) }
    val hostName = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.take(24) ?: "PC"
    Beacon(scope, self = { BrainInfo(brainId, "pc", "PC · $hostName", httpPort, hub.hasTelescope) }, onPeers = hub::setPeers).start()
    val panelUrl = "http://localhost:$httpPort/desk.html?k=${hub.accessKey}"

    val link = Connection(hub, settings, opts.port, scope)
    hub.platform = link
    // Closing the window or Ctrl+C: the telescope must stop before the program goes away.
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking { withTimeoutOrNull(2_000) { runCatching { hub.haltAll() } } }
        link.close()
    })

    // Events and errors of the hub, for whoever looks at the console.
    runBlocking {
        hub.onConnect("consola") { text ->
            val m = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@onConnect
            when (m["type"]?.jsonPrimitive?.content) {
                "event" -> log(m["message"]?.jsonPrimitive?.content.orEmpty())
                "error" -> log("! " + m["message"]?.jsonPrimitive?.content.orEmpty())
            }
        }
    }

    println(
        """
        |
        |  StarBridge en el PC
        |  -------------------
        |  Datos: ${File(home, "settings.properties")}
        |  Panel: $panelUrl
        |  Comandos: q = parar el telescopio y salir | v = abrir las ventanas | qr = QR del iPhone | d = diagnostico
        |
        """.trimMargin(),
    )
    val page = ConnectPage(File(home, "conectar.html"))
    if (!opts.noWindow) {
        openWindows(panelUrl, settings)
        // Without a console window, the icon by the clock opens the panel again and quits.
        Tray.install(onOpen = { openWindows(panelUrl, settings) }, onQr = { page.open() }, onQuit = { kotlin.system.exitProcess(0) })
    }
    scope.launch {
        var shown: String? = null
        while (true) {
            val base = lan()
            if (base != shown) {
                shown = base
                if (base == null) {
                    log("Sin red WiFi/LAN: conecta el PC a la WiFi del iPhone (punto de acceso).")
                } else {
                    val url = hub.shareUrl(base)
                    log("Web para el iPhone: $url")
                    log("  (o escribe ${base.removePrefix("http://")} y el codigo ${hub.displayKey})")
                    // The panel shows the QRs (Ajustes); the page stays for the "qr" command.
                    page.write(url, hub.displayKey)
                }
            }
            delay(10_000)
        }
    }
    scope.launch { link.run() }

    // Console commands (when there is a console).
    while (true) {
        val line = readlnOrNull()?.trim()?.lowercase() ?: break
        when (line) {
            "q", "salir", "exit" -> {
                log("Parando el telescopio y saliendo...")
                kotlin.system.exitProcess(0) // runs the shutdown hook
            }
            "v", "ventana" -> openWindows(panelUrl, settings)
            "qr" -> page.open()
            "d" -> println(hub.diagnosticsText())
            "" -> Unit
            else -> log("Comandos: q = salir | v = abrir las ventanas | qr = QR del iPhone | d = diagnostico")
        }
    }
    // No console (started in the background): keep serving until the process is stopped.
    runBlocking { kotlinx.coroutines.awaitCancellation() }
}

private const val KEY_BRAIN_ID = "brainId"

/** Sections that can have a window of their own (desk.js WIN_NAMES). */
private val SECTION_WINDOWS = setOf("sky", "objects", "photos", "control", "align", "tracking", "slack", "console", "settings")
private const val KEY_LAYOUT = "deskWindows"

private fun windowLayout(settings: FileSettings) =
    settings.get(KEY_LAYOUT).orEmpty().split(',').map { it.trim() }.filter { it in SECTION_WINDOWS }

/** The main panel, then the section windows saved with "Abrir estas al arrancar" (each one goes back to its place). */
private fun openWindows(panelUrl: String, settings: FileSettings) {
    AppWindow.open(panelUrl)
    windowLayout(settings).forEach { section ->
        Thread.sleep(700) // one at a time: Edge opens them in order
        AppWindow.open("$panelUrl&win=$section")
    }
}

/** Finds the cable, connects, and reconnects when it is plugged back or the telescope is switched on. */
private class Connection(
    private val hub: SessionHub,
    private val settings: FileSettings,
    /** --port on the command line: that port and no other. */
    private val wantedPort: String?,
    private val scope: CoroutineScope,
) : SessionHub.PlatformControls {
    @Volatile private var driver: NexStarDriver? = null
    @Volatile private var transport: ComPortTransport? = null
    @Volatile private var portName: String? = null
    private var lastMessage: String? = null

    private fun say(text: String) {
        if (text != lastMessage) {
            log(text)
            lastMessage = text
            publish()
        }
    }

    /** The panel shows the ports and lets the user pick one when it is not obvious. */
    override fun state(): JsonObject = buildJsonObject {
        put("type", "pcState")
        put("connected", driver != null)
        portName?.let { put("port", it) }
        (wantedPort ?: settings.get(KEY_PORT))?.let { put("preferred", it) }
        lastMessage?.let { put("message", it) }
        putJsonArray("ports") {
            ComPortTransport.list().forEach { p -> add(buildJsonObject { put("name", p.name); put("description", p.description) }) }
        }
    }

    override suspend fun handle(type: String, msg: JsonObject): JsonObject? {
        when (type) {
            "pcState" -> return state()
            "pcSetPort" -> {
                val name = msg["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Falta el puerto")
                if (ComPortTransport.list().none { it.name == name }) throw IllegalArgumentException("Ese puerto ya no existe")
                settings.put(KEY_PORT, name)
                driver?.let { if (portName != name) drop(it, "cambio de puerto") }
                say("Probando el puerto $name…")
            }
            else -> throw IllegalArgumentException("Mensaje desconocido: $type")
        }
        return state()
    }

    private fun publish() {
        scope.launch { runCatching { hub.broadcaster(state()) } }
    }

    suspend fun run() {
        while (true) {
            val d = driver
            if (d == null) tryConnect()
            else if (ComPortTransport.list().none { it.name == portName }) {
                drop(d, "cable desconectado")
                say("Cable desconectado. Esperando a que vuelva...")
            } else if (transport?.broken == true) {
                // Same name, dead handle: open the port again.
                drop(d, "se perdio la conexion con el puerto")
                say("Se perdio la conexion con $portName: reconectando...")
            }
            delay(if (driver == null) 4_000 else 2_000)
        }
    }

    @Volatile private var lostLink = false

    private suspend fun drop(d: NexStarDriver, reason: String) {
        lostLink = true
        driver = null
        transport = null
        hub.detachMount(reason)
        d.disconnect()
    }

    private suspend fun tryConnect() {
        val ports = ComPortTransport.list()
        // --port is strict; the port saved last time only wins while it exists (Windows may
        // give the cable another COM number), else the usual automatic choice.
        val saved = settings.get(KEY_PORT)?.takeIf { s -> ports.any { it.name.equals(s, ignoreCase = true) } }
        val pick = if (wantedPort != null) pickPort(ports, wantedPort) else pickPort(ports, saved)
        if (pick == null) {
            say(
                when {
                    ports.isEmpty() -> "Esperando el cable del telescopio (no hay puertos COM)..."
                    wantedPort != null -> "No existe el puerto $wantedPort (hay: ${ports.joinToString { it.name }})"
                    else -> "Hay varios puertos (${ports.joinToString { it.name }}): elige el del cable en Ajustes"
                },
            )
            return
        }
        var transport: ComPortTransport? = null
        var d: NexStarDriver? = null
        try {
            transport = ComPortTransport.open(pick.name)
            d = NexStarDriver(transport, scope)
            val info = d.connect()
            // Back after a broken link: whatever was moving then must stop now.
            if (lostLink) runCatching { d.emergencyStop() }
            hub.attachMount(d, info)
            lostLink = false
            driver = d
            this.transport = transport
            portName = pick.name
            settings.put(KEY_PORT, pick.name)
            say("Telescopio conectado en ${pick.name} (${pick.description}): mando v${info.handControlVersion}${if (info.model == 7) ", SLT" else ""}")
        } catch (e: Exception) {
            d?.disconnect() ?: transport?.close()
            say("El mando no contesta en ${pick.name} (${e.message}). Esta encendido el telescopio? Reintentando...")
        }
    }

    fun close() {
        driver?.disconnect()
        driver = null
    }

    companion object {
        const val KEY_PORT = "comPort"
    }
}

private class Options(val port: String?, val httpPort: Int?, val ip: String?, val noWindow: Boolean) {
    companion object {
        fun parse(args: Array<String>): Options {
            fun value(name: String) = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
            return Options(
                port = value("--port"),
                httpPort = value("--http")?.toIntOrNull(),
                ip = value("--ip"),
                noWindow = "--no-window" in args,
            )
        }
    }
}

/**
 * The computer's address on the WiFi the iPhone uses. Virtual adapters (Hyper-V, WSL, VPNs,
 * VirtualBox…) also have private addresses but the iPhone cannot reach them.
 */
fun lanIp(): String? = runCatching {
    val virtual = Regex("Hyper-V|Virtual|VMware|VirtualBox|vEthernet|WSL|Loopback|TAP|VPN|Bluetooth|Npcap|Docker|Tailscale|ZeroTier|WireGuard", RegexOption.IGNORE_CASE)
    LanAddress.pick(
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !virtual.containsMatchIn(it.displayName.orEmpty()) }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
                    .map { nif.name to it.hostAddress.orEmpty() }
            },
    )
}.getOrNull()

fun log(text: String) {
    val t = java.time.LocalTime.now()
    println("%02d:%02d:%02d  %s".format(t.hour, t.minute, t.second, text))
}
