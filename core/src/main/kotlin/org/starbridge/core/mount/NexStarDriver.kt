package org.starbridge.core.mount

import kotlinx.coroutines.CoroutineScope
import org.starbridge.core.protocol.Command
import org.starbridge.core.protocol.NexStarCodec
import org.starbridge.core.transport.CommandQueue
import org.starbridge.core.transport.ProtocolException
import org.starbridge.core.transport.SerialTransport

/** [MountDriver] for Celestron NexStar hand controls over the RS-232 port (9600 8N1). */
class NexStarDriver(
    private val transport: SerialTransport,
    scope: CoroutineScope,
    timeoutMs: Long = 3500,
) : MountDriver {
    private val queue = CommandQueue(transport, scope, timeoutMs)
    private var info: MountInfo? = null

    private suspend fun run(c: Command, urgent: Boolean = false) = queue.execute(c, urgent)

    override suspend fun connect(): MountInfo {
        val echo = run(NexStarCodec.echo('x'))
        if (echo.singleOrNull()?.toInt() != 'x'.code) throw ProtocolException("El mando no contesta correctamente")
        val v = run(NexStarCodec.getVersion())
        val version = FirmwareVersion(v[0].toInt() and 0xFF, v[1].toInt() and 0xFF)
        val model = if (version.atLeast(2, 2)) run(NexStarCodec.getModel())[0].toInt() and 0xFF else null
        return MountInfo(version, model).also { info = it }
    }

    override fun disconnect() {
        queue.close()
        transport.close()
    }

    private fun requireInfo() = info ?: error("Telescopio no conectado")

    private fun requireSupport(supported: Boolean, what: String) {
        if (!supported) throw UnsupportedCommandException(
            "Tu mando (versión ${requireInfo().handControlVersion}) no admite: $what"
        )
    }

    override suspend fun isAligned() = run(NexStarCodec.isAligned())[0].toInt() == 1

    override suspend fun getRaDec(): RaDec {
        requireSupport(requireInfo().supportsPreciseRaDec, "Precise RA/Dec")
        val (ra, dec) = NexStarCodec.parseAnglePair(run(NexStarCodec.getPreciseRaDec()).decodeToString())
        return RaDec(ra / 15.0, NexStarCodec.signedDeg(dec))
    }

    override suspend fun getAltAz(): AltAz {
        requireSupport(requireInfo().supportsPreciseAltAz, "Precise Az/Alt")
        val (az, alt) = NexStarCodec.parseAnglePair(run(NexStarCodec.getPreciseAltAz()).decodeToString())
        return AltAz(az, NexStarCodec.signedDeg(alt))
    }

    override suspend fun gotoAltAz(target: AltAz) {
        requireSupport(requireInfo().supportsPreciseAltAz, "Precise Az/Alt GoTo")
        run(NexStarCodec.gotoPreciseAltAz(target.azDeg, target.altDeg))
    }

    override suspend fun slewVariable(axis: Axis, direction: Direction, arcsecPerSec: Double) {
        run(NexStarCodec.slewVariable(axis, direction, arcsecPerSec))
    }

    override suspend fun setSite(site: org.starbridge.core.astro.Site) {
        requireSupport(requireInfo().handControlVersion.atLeast(2, 3), "Set location")
        run(NexStarCodec.setLocation(site.latitudeDeg, site.longitudeDeg))
    }

    override suspend fun setDateTime(epochMillis: Long, zone: java.time.ZoneId) {
        requireSupport(requireInfo().handControlVersion.atLeast(2, 3), "Set time")
        val instant = java.time.Instant.ofEpochMilli(epochMillis)
        val rules = zone.rules
        val dst = rules.isDaylightSavings(instant)
        val local = java.time.LocalDateTime.ofInstant(instant, zone)
        val zoneHours = Math.round(rules.getStandardOffset(instant).totalSeconds / 3600.0).toInt()
        run(
            NexStarCodec.setTime(
                local.hour, local.minute, local.second, local.monthValue, local.dayOfMonth, local.year, zoneHours, dst,
            ),
        )
    }

    override suspend fun syncRaDec(position: RaDec) {
        requireSupport(requireInfo().supportsSync, "Sync")
        run(NexStarCodec.syncPreciseRaDec(position.raHours, position.decDeg))
    }

    override suspend fun gotoRaDec(target: RaDec) {
        requireSupport(requireInfo().supportsPreciseRaDec, "Precise GoTo")
        if (!isAligned()) throw IllegalStateException("El mando no está alineado")
        run(NexStarCodec.gotoPreciseRaDec(target.raHours, target.decDeg))
    }

    override suspend fun isGotoInProgress() = run(NexStarCodec.isGotoInProgress()).decodeToString() == "1"

    override suspend fun cancelGoto() {
        run(NexStarCodec.cancelGoto(), urgent = true)
    }

    override suspend fun slew(axis: Axis, direction: Direction, rate: Int) {
        require(rate in 1..9) { "Rate must be 1..9" }
        run(NexStarCodec.slewFixed(axis, direction, rate))
    }

    override suspend fun stop(axis: Axis) {
        run(NexStarCodec.slewFixed(axis, Direction.POSITIVE, 0), urgent = true)
    }

    override suspend fun emergencyStop() {
        // Try every step even if one fails.
        val errors = listOf(
            runCatching { stop(Axis.AZM) },
            runCatching { stop(Axis.ALT) },
            runCatching { cancelGoto() },
            // The hand control's own tracking would otherwise keep the motors turning.
            runCatching { run(NexStarCodec.setTracking(0), urgent = true) },
        ).mapNotNull { it.exceptionOrNull() }
        errors.firstOrNull()?.let { throw it }
    }

    override suspend fun getSite(): org.starbridge.core.astro.Site? {
        if (!requireInfo().handControlVersion.atLeast(2, 3)) return null
        val (lat, lon) = NexStarCodec.decodeLocation(run(NexStarCodec.getLocation()))
        return org.starbridge.core.astro.Site(lat, lon)
    }

    override suspend fun getTracking(): TrackingMode? {
        requireSupport(requireInfo().supportsGetTracking, "Get tracking")
        return TrackingMode.fromCode(run(NexStarCodec.getTracking())[0].toInt() and 0xFF)
    }

    override suspend fun setTracking(mode: TrackingMode) {
        run(NexStarCodec.setTracking(mode.code))
    }

    override suspend fun getMotorVersion(axis: Axis): FirmwareVersion {
        val r = run(NexStarCodec.passThrough(axis.deviceId, NexStarCodec.MC_GET_VER, ByteArray(0), 2))
        return FirmwareVersion(r[0].toInt() and 0xFF, r[1].toInt() and 0xFF)
    }

    override suspend fun getBacklash(axis: Axis): BacklashValues {
        val p = run(NexStarCodec.passThrough(axis.deviceId, NexStarCodec.MC_GET_POS_BACKLASH, ByteArray(0), 1))
        val n = run(NexStarCodec.passThrough(axis.deviceId, NexStarCodec.MC_GET_NEG_BACKLASH, ByteArray(0), 1))
        return BacklashValues(p[0].toInt() and 0xFF, n[0].toInt() and 0xFF)
    }

    override suspend fun setBacklash(axis: Axis, values: BacklashValues) {
        run(NexStarCodec.passThrough(axis.deviceId, NexStarCodec.MC_SET_POS_BACKLASH, byteArrayOf(values.positive.toByte()), 0))
        run(NexStarCodec.passThrough(axis.deviceId, NexStarCodec.MC_SET_NEG_BACKLASH, byteArrayOf(values.negative.toByte()), 0))
    }

    override fun trace(): List<String> = queue.trace()

    override fun linkStats(): LinkStats = queue.stats()

    override suspend fun raw(bytes: ByteArray, responseLength: Int?): ByteArray {
        require(bytes.isNotEmpty()) { "Orden vacía" }
        val spec = when {
            responseLength == null -> org.starbridge.core.protocol.ResponseSpec.Terminated
            bytes[0] == 'P'.code.toByte() -> org.starbridge.core.protocol.ResponseSpec.PassThrough(responseLength)
            else -> org.starbridge.core.protocol.ResponseSpec.Fixed(responseLength)
        }
        return run(Command(bytes, spec))
    }

    override suspend fun getMotorPosition(axis: Axis): Double {
        val r = run(NexStarCodec.passThrough(axis.deviceId, NexStarCodec.MC_GET_POSITION, ByteArray(0), 3))
        return NexStarCodec.bytes24ToDeg(r)
    }
}
