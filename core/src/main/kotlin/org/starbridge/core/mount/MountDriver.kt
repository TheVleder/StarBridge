package org.starbridge.core.mount

/** Everything the app needs from a telescope mount. All calls are serialized by the driver. */
interface MountDriver {
    suspend fun connect(): MountInfo
    fun disconnect()

    suspend fun isAligned(): Boolean
    suspend fun getRaDec(): RaDec

    /**
     * Az/Alt reported by the hand control: real sky coordinates when it is aligned, raw
     * encoder angles relative to the power-on position when it is not.
     */
    suspend fun getAltAz(): AltAz

    /** GoTo in the same frame as [getAltAz]. Works without alignment. */
    suspend fun gotoAltAz(target: AltAz)

    /** Variable-rate slew in arcsec/s (0 stops the axis). Used for tracking. */
    suspend fun slewVariable(axis: Axis, direction: Direction, arcsecPerSec: Double)

    /** Writes the observing site into the hand control. 2.3+ */
    suspend fun setSite(site: org.starbridge.core.astro.Site)

    /** Writes date/time into the hand control. 2.3+ */
    suspend fun setDateTime(epochMillis: Long, zone: java.time.ZoneId)

    /** Sync the hand control's own alignment on a known position. 4.10+ */
    suspend fun syncRaDec(position: RaDec)

    suspend fun gotoRaDec(target: RaDec)
    suspend fun isGotoInProgress(): Boolean
    suspend fun cancelGoto()

    /** Fixed-rate slew, [rate] 1..9. */
    suspend fun slew(axis: Axis, direction: Direction, rate: Int)
    suspend fun stop(axis: Axis)

    /** Emergency stop: both axes to 0 and cancel any GoTo. Jumps the command queue. */
    suspend fun emergencyStop()

    /** Observing site stored in the hand control (lat, lon east-positive), or null if unsupported. */
    suspend fun getSite(): org.starbridge.core.astro.Site?

    suspend fun getTracking(): TrackingMode?
    suspend fun setTracking(mode: TrackingMode)

    // --- Motor controller pass-through (see docs/HOLGURA.md) ---
    suspend fun getMotorVersion(axis: Axis): FirmwareVersion
    suspend fun getBacklash(axis: Axis): BacklashValues
    suspend fun setBacklash(axis: Axis, values: BacklashValues)

    /** Raw motor encoder position in degrees of axis rotation (what the mount *thinks*). */
    suspend fun getMotorPosition(axis: Axis): Double

    /** Recent traffic with the mount, oldest first, for the diagnostics page. */
    fun trace(): List<String> = emptyList()

    /** How the link is doing (latency, errors), or null if unknown. */
    fun linkStats(): LinkStats? = null

    /**
     * Sends a raw command typed in the console and returns its answer (without the '#').
     * [responseLength] null = text up to '#'; otherwise that many binary bytes.
     */
    suspend fun raw(bytes: ByteArray, responseLength: Int?): ByteArray =
        throw UnsupportedOperationException("Esta montura no admite órdenes directas")
}

/** Serial link health: average and last command time, and how many failed. */
data class LinkStats(val averageMs: Double, val lastMs: Long, val commands: Long, val errors: Long)
