package org.starbridge.core.mount

/** Mount axis. [deviceId] is the AUX address of its motor controller. */
enum class Axis(val deviceId: Int) {
    AZM(16),
    ALT(17),
}

/** Positive = clockwise (azimuth) / up (altitude). */
enum class Direction { POSITIVE, NEGATIVE }

enum class TrackingMode(val code: Int) {
    OFF(0), ALT_AZ(1), EQ_NORTH(2), EQ_SOUTH(3);

    companion object {
        fun fromCode(code: Int): TrackingMode? = entries.firstOrNull { it.code == code }
    }
}

/** Hand controller (or motor) firmware version. */
data class FirmwareVersion(val major: Int, val minor: Int) : Comparable<FirmwareVersion> {
    override fun compareTo(other: FirmwareVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor })

    fun atLeast(major: Int, minor: Int) = this >= FirmwareVersion(major, minor)

    override fun toString() = "$major.$minor"
}

data class RaDec(val raHours: Double, val decDeg: Double)

data class AltAz(val azDeg: Double, val altDeg: Double)

/** Native anti-backlash values (0..99) of one axis. */
data class BacklashValues(val positive: Int, val negative: Int) {
    init {
        require(positive in 0..99 && negative in 0..99) { "Backlash values must be 0..99" }
    }
}

data class MountInfo(
    val handControlVersion: FirmwareVersion,
    /** 7 = SLT. Null if the hand control is too old to report it. */
    val model: Int?,
) {
    val supportsPreciseRaDec get() = handControlVersion.atLeast(1, 6)
    val supportsPreciseAltAz get() = handControlVersion.atLeast(2, 2)
    val supportsGetTracking get() = handControlVersion.atLeast(2, 3)
    val supportsSync get() = handControlVersion.atLeast(4, 10)

    /** Model name ("SLT", "AVX"…), or null if unknown. */
    val modelName: String? get() = model?.let { MODEL_NAMES[it] }

    /**
     * German equatorial mount: its axes are RA/Dec, not Az/Alt, so StarBridge's own pointing
     * model does not apply; the hand control aligns, points and tracks.
     */
    val isEquatorial: Boolean get() = model in EQUATORIAL_MODELS

    companion object {
        /** Answers to `m`, as in INDI's Celestron driver (celestrondriver.cpp, get_model). */
        val MODEL_NAMES = mapOf(
            1 to "GPS Series", 3 to "i-Series", 4 to "i-Series SE", 5 to "CGE", 6 to "Advanced GT",
            7 to "SLT", 9 to "CPC", 10 to "GT", 11 to "4/5 SE", 12 to "6/8 SE", 13 to "CGE Pro",
            14 to "CGEM DX", 15 to "LCM", 16 to "Sky Prodigy", 17 to "CPC Deluxe", 18 to "GT 16",
            19 to "StarSeeker", 20 to "AVX", 21 to "Cosmos", 22 to "Evolution", 23 to "CGX",
            24 to "CGXL", 25 to "Astro Fi", 26 to "SkyWatcher",
        )
        /** The models INDI treats as German equatorial (isGem). */
        val EQUATORIAL_MODELS = setOf(5, 6, 13, 14, 20, 23, 24)
    }
}

class UnsupportedCommandException(message: String) : Exception(message)
