package org.starbridge.core.imaging

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.starbridge.core.server.MemorySettings
import org.starbridge.core.sim.SimulatedCamera
import org.starbridge.imaging.Picture
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImagingSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File = Files.createTempDirectory("sb-img").toFile()
    private val sent = CopyOnWriteArrayList<JsonObject>()

    private class FakeHost : ImagingHost {
        @Volatile var thermal = 0
        @Volatile var batteryLevel: Pair<Int, Boolean>? = 80 to false
        val published = CopyOnWriteArrayList<String>()
        override fun jpeg(picture: Picture, quality: Int) = ByteArray(16 + picture.width) { it.toByte() }
        override fun thermalStatus() = thermal
        override fun battery() = batteryLevel
        override fun publish(file: File, mime: String): String { published += file.name; return "Descargas/StarBridge" }
    }

    private class FakeMount : MountLink {
        @Volatile var moving = false
        @Volatile var goto = false
        override fun connected() = true
        override fun moving() = moving || goto
        override fun gotoActive() = goto
        override fun pointing() = 120.0 to 50.0
        override fun latitude() = 40.4
        override fun target() = "M31"
    }

    private val host = FakeHost()
    private val mount = FakeMount()
    private val settings = MemorySettings()

    private fun session(camera: CameraSource = SimulatedCamera(timeScale = 0.0)) =
        ImagingSession(scope, camera, host, settings, dir, emit = { sent += it }, settleMs = 50, pollMs = 20)
            .also { it.mount = mount }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun until(timeoutMs: Long = 120_000, what: String, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("timeout waiting for $what; last state: ${sent.lastOrNull { it["type"]?.jsonPrimitive?.content == "imaging" }}")
        }
    }

    private fun ImagingSession.stack() = state()["stack"]?.jsonObject

    @Test
    fun fullSessionMeasuresFocusesStacksAndSaves() = runBlocking {
        val s = session()
        assertTrue(s.available)
        // The monochrome RAW camera is the recommended one for EAA.
        assertEquals("2", s.camerasMessage()["recommended"]?.jsonPrimitive?.content)
        s.start()
        until(what = "6 stacked frames") { (s.stack()?.get("frames")?.jsonPrimitive?.int ?: 0) >= 6 }
        val st = s.state()
        // Auto exposure explains itself; the lens was focused near the simulated best (0.35 D).
        val auto = assertNotNull(st["auto"]?.jsonObject)
        assertTrue(auto["exposureSec"]!!.jsonPrimitive.double in 0.5..30.0, "auto exposure $auto")
        val focus = st["lensFocus"]!!.jsonPrimitive.double
        assertTrue(kotlin.math.abs(focus - 0.35) <= 0.2, "lens focus $focus")
        val stack = st["stack"]!!.jsonObject
        assertTrue(stack["accepted"]!!.jsonPrimitive.int >= 5, "accepted $stack")
        assertTrue(stack["snrGain"]!!.jsonPrimitive.double > 1.5, "gain $stack")
        // Pictures are served (with the key check done by the hub).
        assertNotNull(s.http("view.jpg"))
        assertNotNull(s.http("thumb.jpg"))
        assertNotNull(s.http("single.jpg"))
        s.pause()
        assertEquals("paused", s.state()["phase"]!!.jsonPrimitive.content)
        val frames = s.stack()!!["frames"]!!.jsonPrimitive.int
        // Save: JPEG + linear TIFF 16 + FITS, downloadable and published.
        val saved = s.save()
        val files = saved["files"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(3, files.size, "$files")
        assertTrue(files.any { it.endsWith(".fits") } && files.any { it.endsWith(".tif") } && files.any { it.endsWith(".jpg") })
        assertTrue(files.all { it.contains("M31") })
        assertEquals(3, host.published.size)
        val fits = assertNotNull(s.http("file/" + files.first { it.endsWith(".fits") }))
        assertEquals("SIMPLE  =", String(fits.bytes, 0, 9, Charsets.US_ASCII))
        assertEquals(null, s.http("file/../secret"))
        // Resume keeps the stack.
        s.resume()
        until(what = "more frames after resume") { (s.stack()?.get("frames")?.jsonPrimitive?.int ?: 0) > frames }
        s.stop()
    }

    @Test
    fun mountMovementIsNeverStackedAndGotoPauses() = runBlocking {
        settings.put("img.focus.2", "0.35") // skip the lens sweep
        val s = session()
        s.updateSettings(buildJsonObject { put("exposureAuto", false); put("exposureSec", 4.0) })
        s.start()
        until(what = "reference") { s.stack()?.get("referenceSet")?.jsonPrimitive?.content == "true" }
        // Manual slew: the loop waits, frames exposed while moving are skipped.
        mount.moving = true
        delay(300)
        mount.moving = false
        until(what = "frames after the slew") { (s.stack()?.get("frames")?.jsonPrimitive?.int ?: 0) >= 5 }
        // GoTo: stacking pauses by itself.
        mount.goto = true
        until(what = "goto pause") { s.state()["phase"]!!.jsonPrimitive.content == "paused" }
        assertTrue(s.state()["gotoPaused"]!!.jsonPrimitive.content == "true")
        mount.goto = false
    }

    @Test
    fun darksAreMadeAndMatched() = runBlocking {
        settings.put("img.focus.2", "0.35")
        val s = session()
        s.updateSettings(buildJsonObject { put("exposureAuto", false); put("exposureSec", 2.0) })
        assertEquals("false", s.state()["darks"]!!.jsonObject["matching"]!!.jsonPrimitive.content)
        s.darks(4)
        until(what = "darks") { s.state()["darks"]!!.jsonObject["matching"]!!.jsonPrimitive.content == "true" }
        until(what = "idle") { s.state()["phase"]!!.jsonPrimitive.content == "idle" }
        // A stack with the dark still works.
        s.start()
        until(what = "stack with darks") { (s.stack()?.get("accepted")?.jsonPrimitive?.int ?: 0) >= 4 }
        s.stop()
    }

    @Test
    fun focusAssistantMeasuresAndHotPhoneSlowsDown() = runBlocking {
        settings.put("img.focus.2", "0.35")
        val s = session()
        s.updateSettings(buildJsonObject { put("exposureAuto", false); put("exposureSec", 2.0) })
        s.focusAssistant()
        until(what = "focus measure") {
            val f = s.state()["focus"]?.jsonObject
            f != null && f["hfr"] != null && f["history"]!!.jsonArray.size >= 3
        }
        assertNotNull(s.http("stars.jpg"))
        s.stop()
        assertEquals(null, s.state()["focus"])
        // Battery empty: capture does not start.
        host.batteryLevel = 3 to false
        s.preview()
        until(what = "battery stop") { s.state()["phase"]!!.jsonPrimitive.content == "idle" }
        assertTrue(s.state()["message"]!!.jsonPrimitive.content.contains("Batería"))
    }

    @Test
    fun cameraWithoutRawOrManualControlStillStacks() = runBlocking {
        val s = session()
        s.select("1") // front camera: YUV only, automatic exposure, max 0.5 s
        s.start()
        until(what = "stack from a YUV camera") { (s.stack()?.get("accepted")?.jsonPrimitive?.int ?: 0) >= 4 }
        val auto = s.state()["auto"]!!.jsonObject
        assertTrue(auto["explanation"]!!.jsonPrimitive.content.contains("no deja"), "$auto")
        s.stop()
    }

    @Test
    fun settingsChangesRestartOrReRender() = runBlocking {
        settings.put("img.focus.0", "0.35")
        val s = session()
        s.select("0") // colour camera
        s.updateSettings(buildJsonObject { put("exposureAuto", false); put("exposureSec", 3.0) })
        s.start()
        until(what = "stack picture") { s.state()["images"]!!.jsonObject["stack"] != null }
        val v0 = s.state()["images"]!!.jsonObject["stack"]!!.jsonPrimitive.int
        s.pause()
        // Look only: re-rendered, same stack.
        val frames = s.stack()!!["frames"]!!.jsonPrimitive.int
        s.updateSettings(buildJsonObject { put("contrast", 1.4) })
        assertTrue(s.state()["images"]!!.jsonObject["stack"]!!.jsonPrimitive.int > v0)
        assertEquals(frames, s.stack()!!["frames"]!!.jsonPrimitive.int)
        // Geometry: new stack.
        s.updateSettings(buildJsonObject { put("color", false) })
        assertEquals(null, s.stack())
        assertEquals(false, s.state()["effective"]!!.jsonObject["color"]!!.jsonPrimitive.content.toBoolean())
        // Settings are remembered per camera.
        assertTrue(settings.get("img.settings.0")!!.contains("\"contrast\":1.4"))
    }
}
