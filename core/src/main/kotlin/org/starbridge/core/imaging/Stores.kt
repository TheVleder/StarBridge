package org.starbridge.core.imaging

import org.starbridge.imaging.DarkProvider
import org.starbridge.imaging.Frame
import org.starbridge.imaging.FrameStore
import org.starbridge.imaging.Image
import org.starbridge.imaging.MasterDark
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ln

/** Compact binary float frames on disk (little overhead, no external format needed). */
internal object FrameFile {
    private const val MAGIC = 0x53424652 // "SBFR"

    fun write(file: File, f: Frame, extra: DoubleArray = DoubleArray(0)) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(f.width); out.writeInt(f.height); out.writeInt(f.channels.size)
            out.writeInt(extra.size); extra.forEach { out.writeDouble(it) }
            val buf = java.nio.ByteBuffer.allocate(f.width * 4)
            for (c in f.channels) for (y in 0 until f.height) {
                buf.clear()
                for (x in 0 until f.width) buf.putFloat(c.data[y * f.width + x])
                out.write(buf.array(), 0, buf.position())
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun read(file: File): Pair<Frame, DoubleArray>? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
            require(inp.readInt() == MAGIC)
            val w = inp.readInt(); val h = inp.readInt(); val ch = inp.readInt()
            require(w in 1..20_000 && h in 1..20_000 && ch in 1..3)
            val extra = DoubleArray(inp.readInt().coerceIn(0, 64)) { inp.readDouble() }
            val row = ByteArray(w * 4)
            val channels = List(ch) {
                val d = FloatArray(w * h)
                for (y in 0 until h) {
                    inp.readFully(row)
                    val bb = java.nio.ByteBuffer.wrap(row)
                    for (x in 0 until w) d[y * w + x] = bb.getFloat()
                }
                Image(w, h, d)
            }
            Frame(channels) to extra
        }
    }.getOrNull()
}

/**
 * Master darks on disk, one per (camera, ISO, exposure, binning, colour). A dark is used for
 * light frames of the same camera/ISO/size with an exposure within ×1.5 (the dark scaling
 * of the calibration absorbs the difference).
 */
class DarkLibrary(private val dir: File) : DarkProvider {
    data class Key(val cameraId: String, val iso: Int, val exposureSec: Double, val binning: Int, val color: Boolean) {
        val fileName: String get() =
            "dark_" + cameraId.replace(Regex("[^A-Za-z0-9]"), "-") +
                "_iso$iso" + "_${Math.round(exposureSec * 1000)}ms" + "_b$binning" + (if (color) "_c" else "_m") + ".sbd"
    }

    data class Entry(val key: Key, val frames: Int, val file: File)

    /** Camera and conversion of the current session (darks of other setups never match). */
    @Volatile var context: Triple<String, Int, Boolean>? = null

    private var cached: Pair<File, MasterDark>? = null

    fun save(key: Key, dark: MasterDark) {
        FrameFile.write(File(dir, key.fileName), dark.frame, doubleArrayOf(dark.exposureSec, dark.iso.toDouble(), dark.frames.toDouble()))
        synchronized(this) { cached = null }
    }

    fun list(): List<Entry> {
        val re = Regex("dark_(.+)_iso(\\d+)_(\\d+)ms_b(\\d)_([cm])\\.sbd")
        return (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val m = re.matchEntire(f.name) ?: return@mapNotNull null
            val (cam, iso, ms, bin, c) = m.destructured
            Entry(Key(cam, iso.toInt(), ms.toDouble() / 1000, bin.toInt(), c == "c"), 0, f)
        }.sortedBy { it.key.exposureSec }
    }

    fun delete(all: Boolean, cameraId: String? = null) {
        list().filter { all || it.key.cameraId == cameraId?.replace(Regex("[^A-Za-z0-9]"), "-") }.forEach { it.file.delete() }
        synchronized(this) { cached = null }
    }

    fun has(cameraId: String, iso: Int, exposureSec: Double, binning: Int, color: Boolean): Boolean =
        best(cameraId, iso, exposureSec, binning, color) != null

    private fun best(cameraId: String, iso: Int, exposureSec: Double, binning: Int, color: Boolean): Entry? {
        val cam = cameraId.replace(Regex("[^A-Za-z0-9]"), "-")
        return list().filter {
            it.key.cameraId == cam && it.key.iso == iso && it.key.binning == binning && it.key.color == color &&
                it.key.exposureSec / exposureSec in 0.66..1.5
        }.minByOrNull { abs(ln(it.key.exposureSec / exposureSec)) }
    }

    override fun find(exposureSec: Double, iso: Int, width: Int, height: Int, channels: Int): MasterDark? {
        val (cam, bin, color) = context ?: return null
        val e = best(cam, iso, exposureSec, bin, color) ?: return null
        synchronized(this) {
            cached?.let { (f, d) -> if (f == e.file) return d.takeIf { it.frame.width == width && it.frame.height == height && it.frame.channels.size == channels } }
            val (frame, extra) = FrameFile.read(e.file) ?: return null
            val d = MasterDark(frame, extra.getOrElse(0) { e.key.exposureSec }, extra.getOrElse(1) { iso.toDouble() }.toInt(), extra.getOrElse(2) { 0.0 }.toInt())
            cached = e.file to d
            return d.takeIf { frame.width == width && frame.height == height && frame.channels.size == channels }
        }
    }
}

/** Rejected frames kept on disk (not in memory: 2 MP float frames are 8 MB each) for "Recuperar". */
class DiskFrameStore(private val dir: File, private val max: Int = 40) : FrameStore {
    private val ids = ArrayDeque<Int>()

    init { clear() }

    fun clear() {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        synchronized(ids) { ids.clear() }
    }

    override fun save(id: Int, frame: Frame) {
        runCatching { FrameFile.write(File(dir, "$id.sbf"), frame) }.onFailure { return }
        synchronized(ids) {
            ids.addLast(id)
            while (ids.size > max) File(dir, "${ids.removeFirst()}.sbf").delete()
        }
    }

    override fun load(id: Int): Frame? = FrameFile.read(File(dir, "$id.sbf"))?.first

    override fun delete(id: Int) {
        File(dir, "$id.sbf").delete()
        synchronized(ids) { ids.remove(id) }
    }

    fun has(id: Int) = synchronized(ids) { id in ids }
}
