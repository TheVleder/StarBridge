package org.starbridge.dev

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.starbridge.core.astro.Astro
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.control.OrientationSensor
import org.starbridge.core.imaging.ImagingHost
import org.starbridge.core.imaging.ImagingSession
import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.NexStarDriver
import org.starbridge.core.pointing.PointingParams
import org.starbridge.core.server.MemorySettings
import org.starbridge.core.server.SessionHub
import org.starbridge.core.sim.SimulatedCamera
import org.starbridge.core.sim.SimulatedHandController
import org.starbridge.core.sim.SimulatedMotionSensor
import org.starbridge.imaging.Picture
import org.starbridge.server.starBridge
import java.io.File

/**
 * Development server: the real hub + brain + NexStar driver talking to the simulated hand
 * control (unaligned, tilted base, gear slack), serving the web UI. For UI work on a PC.
 *   ./gradlew :devserver:run   → http://localhost:8099 (phone page), /desk.html (computer panel)
 */
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val site = Site(40.4168, -3.7038)
    val sim = SimulatedHandController(
        site, System::currentTimeMillis, backlashDeg = 0.4, aligned = false,
        geometry = PointingParams(azOffset = -137.0, altOffset = 12.5, tiltEast = 1.2, tiltNorth = -0.7),
    )
    // The access key survives restarts (open pages keep working); everything else starts fresh.
    val keyFile = File(System.getProperty("java.io.tmpdir"), "starbridge-dev-key.txt")
    val hub = SessionHub(scope, Catalog.loadDefault(), settings = KeepKeySettings(keyFile))
    hub.motionSensor = SimulatedMotionSensor(sim)
    // Phone compass 5° off, accelerometer 0.8° off.
    hub.orientationSensor = OrientationSensor {
        synchronized(sim) { sim.truePointing() }.let { AltAz(Astro.norm360(it.azDeg + 5.0), it.altDeg - 0.8) }
    }
    // Simulated phone camera looking at a synthetic sky: the whole imaging pipeline runs.
    val dataDir = File(System.getProperty("java.io.tmpdir"), "starbridge-dev").apply { mkdirs() }
    hub.imaging = ImagingSession(scope, SimulatedCamera(), DevImagingHost(dataDir), MemorySettings(), dataDir, hub.broadcaster)
    runBlocking {
        val driver = NexStarDriver(LockedTransport(sim), scope)
        hub.attachMount(driver, driver.connect())
        hub.updateGps(site, 4.0)
    }

    val web = File("app/src/main/assets/web")
    val desk = File("desktop/src/main/resources/desk") // the computer's panel
    val port = System.getenv("STARBRIDGE_DEV_PORT")?.toIntOrNull() ?: 8099
    val base = "http://localhost:$port"
    println("StarBridge dev server: ${hub.shareUrl(base)}  (web: ${web.absolutePath})")
    // Same routes as the app; the web UI is read from disk so edits show up on reload.
    // Announced on the network like a real brain (as an Android by default), reachable on this computer.
    val kind = System.getenv("STARBRIDGE_DEV_KIND") ?: "android"
    hub.brainKind = kind
    val devId = "dev$port"
    org.starbridge.core.net.Beacon(scope, self = {
        org.starbridge.core.net.BrainInfo(devId, kind, if (kind == "pc") "PC simulado" else "Android simulado", port, hub.hasTelescope, host = "127.0.0.1")
    }, onPeers = hub::setPeers).start()
    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        starBridge(hub, { base }) { name ->
            (File(web, name).takeIf { it.isFile } ?: File(desk, name).takeIf { it.isFile })?.readBytes()
        }
    }.start(wait = true)
}

/** The simulator is not thread-safe; the sensors read it from other threads. */
private class LockedTransport(private val sim: SimulatedHandController) :
    org.starbridge.core.transport.SerialTransport {
    override suspend fun write(data: ByteArray) = synchronized(sim) { runBlocking { sim.write(data) } }
    override suspend fun read(timeoutMs: Long): Int? = synchronized(sim) { runBlocking { sim.read(timeoutMs) } }
    override suspend fun clearInput() = synchronized(sim) { runBlocking { sim.clearInput() } }
    override fun close() {}
}

/** JPEG with ImageIO; exports stay in the temp folder. */
private class DevImagingHost(private val dir: File) : ImagingHost {
    override fun jpeg(picture: Picture, quality: Int): ByteArray {
        val img = java.awt.image.BufferedImage(picture.width, picture.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, picture.width, picture.height, picture.argb, 0, picture.width)
        val out = java.io.ByteArrayOutputStream()
        val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / 100f
        }
        javax.imageio.ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, javax.imageio.IIOImage(img, null, null), params)
        }
        writer.dispose()
        return out.toByteArray()
    }

    override fun thermalStatus() = 0
    override fun battery() = 76 to false
    override fun publish(file: File, mime: String) = "carpeta de exportación (${dir.name})"
}

/** In-memory settings, except the access key, kept in [file] so a restart does not lock open pages out. */
private class KeepKeySettings(private val file: File) : org.starbridge.core.server.SettingsStore {
    private val mem = MemorySettings()
    override fun get(key: String): String? =
        if (key == SessionHub.KEY_ACCESS) runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } else mem.get(key)
    override fun put(key: String, value: String) {
        if (key == SessionHub.KEY_ACCESS) runCatching { file.writeText(value) } else mem.put(key, value)
    }
}
