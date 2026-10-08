package org.starbridge.core.astro

import org.starbridge.core.mount.AltAz
import org.starbridge.core.mount.RaDec
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Site(val latitudeDeg: Double, val longitudeDeg: Double) // longitude east-positive

/** Low-precision (arcminute-level) astronomy, plenty for pointing a telescope. */
object Astro {
    private const val DEG = Math.PI / 180.0

    fun julianDate(epochMillis: Long) = epochMillis / 86_400_000.0 + 2_440_587.5

    /** Days since J2000.0. */
    fun daysSinceJ2000(epochMillis: Long) = julianDate(epochMillis) - 2_451_545.0

    /** Greenwich mean sidereal time in degrees. */
    fun gmstDeg(epochMillis: Long): Double {
        val d = daysSinceJ2000(epochMillis)
        return norm360(280.46061837 + 360.98564736629 * d)
    }

    fun localSiderealDeg(epochMillis: Long, site: Site) = norm360(gmstDeg(epochMillis) + site.longitudeDeg)

    fun raDecToAltAz(p: RaDec, site: Site, epochMillis: Long): AltAz {
        val ha = (localSiderealDeg(epochMillis, site) - p.raHours * 15.0) * DEG
        val dec = p.decDeg * DEG
        val lat = site.latitudeDeg * DEG
        val alt = asin(sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(ha))
        // Azimuth measured from north, increasing east.
        val az = atan2(-sin(ha) * cos(dec), cos(lat) * sin(dec) - sin(lat) * cos(dec) * cos(ha))
        return AltAz(norm360(az / DEG), alt / DEG)
    }

    fun altAzToRaDec(p: AltAz, site: Site, epochMillis: Long): RaDec {
        val alt = p.altDeg * DEG
        val az = p.azDeg * DEG
        val lat = site.latitudeDeg * DEG
        val dec = asin(sin(alt) * sin(lat) + cos(alt) * cos(lat) * cos(az))
        val ha = atan2(-sin(az) * cos(alt), cos(lat) * sin(alt) - sin(lat) * cos(alt) * cos(az))
        val ra = norm360(localSiderealDeg(epochMillis, site) - ha / DEG)
        return RaDec(ra / 15.0, dec / DEG)
    }

    /**
     * Precesses J2000 catalog coordinates to the equinox of [epochMillis] (IAU 1976, rigorous
     * rotation). The hand control works in the equinox of date ("JNow"); without this a
     * GoTo would be off by ~20 arcmin in 2026.
     */
    fun precessFromJ2000(p: RaDec, epochMillis: Long): RaDec {
        val t = daysSinceJ2000(epochMillis) / 36525.0
        val arcsec = DEG / 3600.0
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * arcsec
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * arcsec
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * arcsec
        val ra0 = p.raHours * 15.0 * DEG
        val dec0 = p.decDeg * DEG
        val a = cos(dec0) * sin(ra0 + zeta)
        val b = cos(theta) * cos(dec0) * cos(ra0 + zeta) - sin(theta) * sin(dec0)
        val c = sin(theta) * cos(dec0) * cos(ra0 + zeta) + cos(theta) * sin(dec0)
        val ra = atan2(a, b) + z
        val dec = asin(c.coerceIn(-1.0, 1.0))
        return RaDec(norm360(ra / DEG) / 15.0, dec / DEG)
    }

    /** Apparent altitude seen through the atmosphere (Saemundsson, standard conditions). */
    fun refract(trueAltDeg: Double): Double {
        if (trueAltDeg < -1.0) return trueAltDeg
        val h = trueAltDeg
        val rArcmin = 1.02 / kotlin.math.tan((h + 10.3 / (h + 5.11)) * DEG)
        return h + rArcmin / 60.0
    }

    /** True (geometric) altitude from an apparent one (Bennett). */
    fun unrefract(apparentAltDeg: Double): Double {
        if (apparentAltDeg < -1.0) return apparentAltDeg
        val h = apparentAltDeg
        val rArcmin = 1.0 / kotlin.math.tan((h + 7.31 / (h + 4.4)) * DEG)
        return h - rArcmin / 60.0
    }

    /** Apparent topocentric Az/Alt (with refraction) of an equinox-of-date position. */
    fun apparentAltAz(p: RaDec, site: Site, epochMillis: Long): AltAz {
        val geo = raDecToAltAz(p, site, epochMillis)
        return AltAz(geo.azDeg, refract(geo.altDeg))
    }

    /** Equinox-of-date RA/Dec of an apparent (refracted) Az/Alt. */
    fun apparentToRaDec(p: AltAz, site: Site, epochMillis: Long): RaDec =
        altAzToRaDec(AltAz(p.azDeg, unrefract(p.altDeg)), site, epochMillis)

    /** Compass text for an azimuth, in Spanish (N, NE, E, SE, S, SO, O, NO). */
    fun compass(azDeg: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return names[((norm360(azDeg) + 22.5) / 45.0).toInt() % 8]
    }

    fun norm360(x: Double): Double {
        val r = x % 360.0
        return if (r < 0) r + 360.0 else r
    }
}
