package org.starbridge.core.protocol

import org.starbridge.core.mount.Axis
import org.starbridge.core.mount.Direction
import kotlin.math.roundToLong

/** How the hand control terminates the answer to a command. */
sealed interface ResponseSpec {
    /** Variable-length ASCII text terminated by '#'. */
    data object Terminated : ResponseSpec

    /** Exactly [length] binary bytes followed by '#'. Binary bytes may themselves be '#'. */
    data class Fixed(val length: Int) : ResponseSpec

    /**
     * 'P' pass-through: [length] bytes then '#'. If the device did not answer, the hand
     * control sends one extra byte before '#' and the data is garbage.
     */
    data class PassThrough(val length: Int) : ResponseSpec
}

class Command(val bytes: ByteArray, val response: ResponseSpec) {
    override fun toString() = "Command(${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }})"
}

/**
 * Encodes NexStar hand control commands and decodes their answers.
 * Sources: Celestron "NexStar Communication Protocol" and the community "NexStar AUX Command Set".
 */
object NexStarCodec {
    const val TERMINATOR = '#'.code

    // Motor controller message ids (pass-through).
    const val MC_GET_POSITION = 0x01
    const val MC_VAR_RATE_POS = 0x06
    const val MC_VAR_RATE_NEG = 0x07
    const val MC_SET_POS_BACKLASH = 0x10
    const val MC_SET_NEG_BACKLASH = 0x11
    const val MC_SLEW_DONE = 0x13
    const val MC_MOVE_POS = 0x24
    const val MC_MOVE_NEG = 0x25
    const val MC_GET_POS_BACKLASH = 0x40
    const val MC_GET_NEG_BACKLASH = 0x41
    const val MC_GET_VER = 0xFE

    fun echo(c: Char) = Command(byteArrayOf('K'.code.toByte(), c.code.toByte()), ResponseSpec.Fixed(1))
    fun getVersion() = Command(ascii("V"), ResponseSpec.Fixed(2))
    fun getModel() = Command(ascii("m"), ResponseSpec.Fixed(1))
    fun isAligned() = Command(ascii("J"), ResponseSpec.Fixed(1))
    fun isGotoInProgress() = Command(ascii("L"), ResponseSpec.Terminated)
    fun cancelGoto() = Command(ascii("M"), ResponseSpec.Terminated)
    fun getPreciseRaDec() = Command(ascii("e"), ResponseSpec.Terminated)
    fun getPreciseAltAz() = Command(ascii("z"), ResponseSpec.Terminated)
    fun getTracking() = Command(ascii("t"), ResponseSpec.Fixed(1))
    fun setTracking(mode: Int) = Command(byteArrayOf('T'.code.toByte(), mode.toByte()), ResponseSpec.Terminated)

    /** Site stored in the hand control: lat d,m,s,N/S, lon d,m,s,E/W (8 bytes). 2.3+. */
    fun getLocation() = Command(ascii("w"), ResponseSpec.Fixed(8))

    fun decodeLocation(b: ByteArray): Pair<Double, Double> {
        val u = b.map { it.toInt() and 0xFF }
        val lat = (u[0] + u[1] / 60.0 + u[2] / 3600.0) * if (u[3] == 1) -1 else 1
        val lon = (u[4] + u[5] / 60.0 + u[6] / 3600.0) * if (u[7] == 1) -1 else 1
        return lat to lon
    }

    fun encodeLocation(latDeg: Double, lonDeg: Double): ByteArray {
        fun dms(x: Double): List<Int> {
            val total = kotlin.math.round(kotlin.math.abs(x) * 3600).toInt()
            return listOf(total / 3600, (total % 3600) / 60, total % 60)
        }
        return (dms(latDeg) + (if (latDeg < 0) 1 else 0) + dms(lonDeg) + (if (lonDeg < 0) 1 else 0))
            .map { it.toByte() }.toByteArray()
    }

    /** GoTo in mount Az/Alt (raw encoder frame when unaligned). 2.2+ */
    fun gotoPreciseAltAz(azDeg: Double, altDeg: Double) =
        Command(ascii("b${degToHex32(azDeg)},${degToHex32(altDeg)}"), ResponseSpec.Terminated)

    /** Sync the hand control's alignment on a known object. 4.10+ */
    fun syncPreciseRaDec(raHours: Double, decDeg: Double) =
        Command(ascii("s${degToHex32(raHours * 15.0)},${degToHex32(decDeg)}"), ResponseSpec.Terminated)

    fun setLocation(latDeg: Double, lonDeg: Double) =
        Command(byteArrayOf('W'.code.toByte()) + encodeLocation(latDeg, lonDeg), ResponseSpec.Terminated)

    /**
     * 'H' + hour, min, sec, month, day, year-2000, zone (256+zone if negative), dst.
     * [localFields] are local civil time; [zoneHours] the standard-time offset from UTC.
     */
    fun setTime(
        hour: Int, minute: Int, second: Int, month: Int, day: Int, year: Int, zoneHours: Int, dst: Boolean,
    ): Command {
        val zone = if (zoneHours < 0) 256 + zoneHours else zoneHours
        val b = intArrayOf(hour, minute, second, month, day, year - 2000, zone, if (dst) 1 else 0)
        return Command(byteArrayOf('H'.code.toByte()) + ByteArray(8) { b[it].toByte() }, ResponseSpec.Terminated)
    }

    fun gotoPreciseRaDec(raHours: Double, decDeg: Double) =
        Command(ascii("r${degToHex32(raHours * 15.0)},${degToHex32(decDeg)}"), ResponseSpec.Terminated)

    /** Fixed-rate slew, rate 0..9 (0 stops the axis). */
    fun slewFixed(axis: Axis, dir: Direction, rate: Int): Command {
        require(rate in 0..9) { "Rate must be 0..9" }
        val id = if (dir == Direction.POSITIVE) MC_MOVE_POS else MC_MOVE_NEG
        return passThrough(axis.deviceId, id, byteArrayOf(rate.toByte()), 0)
    }

    /** Variable-rate slew in arcseconds per second. */
    fun slewVariable(axis: Axis, dir: Direction, arcsecPerSec: Double): Command {
        val v = (arcsecPerSec * 4).roundToLong().coerceIn(0, 0xFFFF).toInt()
        val id = if (dir == Direction.POSITIVE) MC_VAR_RATE_POS else MC_VAR_RATE_NEG
        return passThrough(axis.deviceId, id, byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte()), 0)
    }

    /** 'P', 1 + data.size, device, msgId, d1, d2, d3, responseLength. */
    fun passThrough(device: Int, msgId: Int, data: ByteArray, responseLength: Int): Command {
        require(data.size <= 3) { "Pass-through carries at most 3 data bytes" }
        val out = ByteArray(8)
        out[0] = 'P'.code.toByte()
        out[1] = (1 + data.size).toByte()
        out[2] = device.toByte()
        out[3] = msgId.toByte()
        data.copyInto(out, 4)
        out[7] = responseLength.toByte()
        return Command(out, ResponseSpec.PassThrough(responseLength))
    }

    // --- Angle conversions -------------------------------------------------

    /** Angle → 8 hex digits (fraction of a revolution × 2^32; only the upper 24 bits are used). */
    fun degToHex32(deg: Double): String {
        val frac = normalize360(deg) / 360.0
        val v24 = (frac * (1L shl 24)).roundToLong() and 0xFFFFFF
        return "%08X".format(v24 shl 8)
    }

    /** Hex fraction of a revolution (4 or 8 digits) → degrees 0..360. */
    fun hexToDeg(hex: String): Double {
        val bits = hex.length * 4
        return hex.toLong(16).toDouble() / (1L shl bits).toDouble() * 360.0
    }

    /** 24-bit motor position (3 bytes, big endian) → degrees. */
    fun bytes24ToDeg(b: ByteArray): Double {
        require(b.size == 3)
        val v = ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        return v.toDouble() / (1 shl 24) * 360.0
    }

    fun degToBytes24(deg: Double): ByteArray {
        val v = ((normalize360(deg) / 360.0) * (1 shl 24)).roundToLong().toInt() and 0xFFFFFF
        return byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    /** Dec/Alt come back as 0..360; above 180 means negative. */
    fun signedDeg(deg: Double) = if (deg > 180.0) deg - 360.0 else deg

    fun normalize360(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Parses "AAAAAAAA,BBBBBBBB" (or the 4-digit form) into two angles in degrees (0..360). */
    fun parseAnglePair(text: String): Pair<Double, Double> {
        val parts = text.trim().split(",")
        require(parts.size == 2) { "Bad angle pair: $text" }
        return hexToDeg(parts[0]) to hexToDeg(parts[1])
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
}
