package org.starbridge.core.astro

import org.starbridge.core.mount.RaDec
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geocentric positions of the Sun, Moon and planets using Paul Schlyter's simplified
 * orbital elements ("How to compute planetary positions"). Accuracy ~1-2 arcmin
 * (Moon a few arcmin), enough to land a planet in a low-power eyepiece.
 */
object Planets {
    enum class Body(val displayName: String) {
        SUN("Sun"), MOON("Moon"), MERCURY("Mercury"), VENUS("Venus"), MARS("Mars"),
        JUPITER("Jupiter"), SATURN("Saturn"), URANUS("Uranus"), NEPTUNE("Neptune"),
    }

    private const val DEG = Math.PI / 180.0

    private class Elements(
        val n: Double, val i: Double, val w: Double, val a: Double, val e: Double, val m: Double,
    )

    /** Schlyter's day number (epoch 1999-12-31 0h UT). */
    private fun dayNumber(epochMillis: Long) = Astro.julianDate(epochMillis) - 2_451_543.5

    private fun elements(body: Body, d: Double): Elements = when (body) {
        Body.SUN -> Elements(0.0, 0.0, 282.9404 + 4.70935e-5 * d, 1.0, 0.016709 - 1.151e-9 * d, 356.0470 + 0.9856002585 * d)
        Body.MOON -> Elements(125.1228 - 0.0529538083 * d, 5.1454, 318.0634 + 0.1643573223 * d, 60.2666, 0.054900, 115.3654 + 13.0649929509 * d)
        Body.MERCURY -> Elements(48.3313 + 3.24587e-5 * d, 7.0047 + 5.00e-8 * d, 29.1241 + 1.01444e-5 * d, 0.387098, 0.205635 + 5.59e-10 * d, 168.6562 + 4.0923344368 * d)
        Body.VENUS -> Elements(76.6799 + 2.46590e-5 * d, 3.3946 + 2.75e-8 * d, 54.8910 + 1.38374e-5 * d, 0.723330, 0.006773 - 1.302e-9 * d, 48.0052 + 1.6021302244 * d)
        Body.MARS -> Elements(49.5574 + 2.11081e-5 * d, 1.8497 - 1.78e-8 * d, 286.5016 + 2.92961e-5 * d, 1.523688, 0.093405 + 2.516e-9 * d, 18.6021 + 0.5240207766 * d)
        Body.JUPITER -> Elements(100.4542 + 2.76854e-5 * d, 1.3030 - 1.557e-7 * d, 273.8777 + 1.64505e-5 * d, 5.20256, 0.048498 + 4.469e-9 * d, 19.8950 + 0.0830853001 * d)
        Body.SATURN -> Elements(113.6634 + 2.38980e-5 * d, 2.4886 - 1.081e-7 * d, 339.3939 + 2.97661e-5 * d, 9.55475, 0.055546 - 9.499e-9 * d, 316.9670 + 0.0334442282 * d)
        Body.URANUS -> Elements(74.0005 + 1.3978e-5 * d, 0.7733 + 1.9e-8 * d, 96.6612 + 3.0565e-5 * d, 19.18171 - 1.55e-8 * d, 0.047318 + 7.45e-9 * d, 142.5905 + 0.011725806 * d)
        Body.NEPTUNE -> Elements(131.7806 + 3.0173e-5 * d, 1.7700 - 2.55e-7 * d, 272.8461 - 6.027e-6 * d, 30.05826 + 3.313e-8 * d, 0.008606 + 2.15e-9 * d, 260.2471 + 0.005995147 * d)
    }

    private data class Vec(val x: Double, val y: Double, val z: Double)

    /** Position in the orbital frame rotated to ecliptic coordinates (heliocentric, or geocentric for Moon/Sun). */
    private fun eclipticPosition(el: Elements): Vec {
        val m = Astro.norm360(el.m) * DEG
        var ea = m + el.e * sin(m) * (1.0 + el.e * cos(m))
        repeat(5) { ea -= (ea - el.e * sin(ea) - m) / (1 - el.e * cos(ea)) }
        val xv = el.a * (cos(ea) - el.e)
        val yv = el.a * sqrt(1 - el.e * el.e) * sin(ea)
        val v = atan2(yv, xv)
        val r = sqrt(xv * xv + yv * yv)
        val n = el.n * DEG
        val i = el.i * DEG
        val vw = v + el.w * DEG
        return Vec(
            r * (cos(n) * cos(vw) - sin(n) * sin(vw) * cos(i)),
            r * (sin(n) * cos(vw) + cos(n) * sin(vw) * cos(i)),
            r * (sin(vw) * sin(i)),
        )
    }

    /** Geocentric apparent RA/Dec. For the Moon use [topocentric]: parallax is up to ~1°. */
    fun position(body: Body, epochMillis: Long): RaDec = geocentric(body, epochMillis).first

    /** RA/Dec as seen from [site] (only differs noticeably for the Moon). */
    fun topocentric(body: Body, epochMillis: Long, site: Site): RaDec {
        val (geo, distanceEarthRadii) = geocentric(body, epochMillis)
        if (body != Body.MOON) return geo
        val mpar = kotlin.math.asin(1.0 / distanceEarthRadii)
        val lat = site.latitudeDeg * DEG
        val gclat = lat - 0.1924 * DEG * sin(2 * lat)
        val rho = 0.99833 + 0.00167 * cos(2 * lat)
        val ra = geo.raHours * 15.0 * DEG
        val dec = geo.decDeg * DEG
        val ha = Astro.localSiderealDeg(epochMillis, site) * DEG - ra
        val topRa = ra - mpar * rho * cos(gclat) * sin(ha) / cos(dec)
        val topDec = if (kotlin.math.abs(gclat) < 1e-9) {
            dec - mpar * rho * sin(-dec) * cos(ha)
        } else {
            val g = kotlin.math.atan(kotlin.math.tan(gclat) / cos(ha))
            dec - mpar * rho * sin(gclat) * sin(g - dec) / sin(g)
        }
        return RaDec(Astro.norm360(topRa / DEG) / 15.0, topDec / DEG)
    }

    /** Returns geocentric RA/Dec and distance (Earth radii for the Moon, AU otherwise). */
    private fun geocentric(body: Body, epochMillis: Long): Pair<RaDec, Double> {
        val d = dayNumber(epochMillis)
        val sun = eclipticPosition(elements(Body.SUN, d)) // geocentric Sun == -(heliocentric Earth)
        val geo = when (body) {
            Body.SUN -> eclipticPosition(elements(body, d))
            Body.MOON -> moonWithPerturbations(d)
            else -> eclipticPosition(elements(body, d)).let { Vec(it.x + sun.x, it.y + sun.y, it.z + sun.z) }
        }
        val ecl = (23.4393 - 3.563e-7 * d) * DEG
        val xe = geo.x
        val ye = geo.y * cos(ecl) - geo.z * sin(ecl)
        val ze = geo.y * sin(ecl) + geo.z * cos(ecl)
        val ra = Astro.norm360(atan2(ye, xe) / DEG)
        val dec = atan2(ze, sqrt(xe * xe + ye * ye)) / DEG
        return RaDec(ra / 15.0, dec) to sqrt(geo.x * geo.x + geo.y * geo.y + geo.z * geo.z)
    }

    /** Moon with the main perturbation terms (Schlyter), otherwise errors reach ~1-2°. */
    private fun moonWithPerturbations(d: Double): Vec {
        val moon = elements(Body.MOON, d)
        val sunEl = elements(Body.SUN, d)
        val raw = eclipticPosition(moon)
        var lon = atan2(raw.y, raw.x) / DEG
        var lat = atan2(raw.z, sqrt(raw.x * raw.x + raw.y * raw.y)) / DEG
        var r = sqrt(raw.x * raw.x + raw.y * raw.y + raw.z * raw.z)

        val ms = Astro.norm360(sunEl.m) * DEG
        val mm = Astro.norm360(moon.m) * DEG
        val ls = (sunEl.n + sunEl.w + sunEl.m) * DEG
        val lm = (moon.n + moon.w + moon.m) * DEG
        val dd = lm - ls
        val f = lm - moon.n * DEG

        lon += -1.274 * sin(mm - 2 * dd) + 0.658 * sin(2 * dd) - 0.186 * sin(ms) -
            0.059 * sin(2 * mm - 2 * dd) - 0.057 * sin(mm - 2 * dd + ms) + 0.053 * sin(mm + 2 * dd) +
            0.046 * sin(2 * dd - ms) + 0.041 * sin(mm - ms) - 0.035 * sin(dd) -
            0.031 * sin(mm + ms) - 0.015 * sin(2 * f - 2 * dd) + 0.011 * sin(mm - 4 * dd)
        lat += -0.173 * sin(f - 2 * dd) - 0.055 * sin(mm - f - 2 * dd) - 0.046 * sin(mm + f - 2 * dd) +
            0.033 * sin(f + 2 * dd) + 0.017 * sin(2 * mm + f)
        r += -0.58 * cos(mm - 2 * dd) - 0.46 * cos(2 * dd)

        val lo = lon * DEG
        val la = lat * DEG
        return Vec(r * cos(lo) * cos(la), r * sin(lo) * cos(la), r * sin(la))
    }
}
