package org.starbridge.core

import org.starbridge.core.astro.Astro
import org.starbridge.core.astro.Planets
import org.starbridge.core.astro.Site
import org.starbridge.core.catalog.Catalog
import org.starbridge.core.mount.RaDec
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AstroCatalogTest {
    private val j2000 = 946_728_000_000L // 2000-01-01 12:00 UT

    @Test
    fun gmstAtJ2000() {
        assertTrue(abs(Astro.gmstDeg(j2000) - 280.46) < 0.01)
    }

    @Test
    fun altAzRoundTrip() {
        val site = Site(40.4, -3.7)
        val p = RaDec(5.5, 22.0)
        val back = Astro.altAzToRaDec(Astro.raDecToAltAz(p, site, j2000), site, j2000)
        assertTrue(abs(back.raHours - p.raHours) < 1e-6 && abs(back.decDeg - p.decDeg) < 1e-6)
    }

    @Test
    fun sunAtJ2000() {
        // Sun on 2000-01-01 12:00 UT: RA ≈ 18h45m, Dec ≈ -23.0°
        val s = Planets.position(Planets.Body.SUN, j2000)
        assertTrue(abs(s.raHours - 18.75) < 0.05, "ra=${s.raHours}")
        assertTrue(abs(s.decDeg + 23.0) < 0.2, "dec=${s.decDeg}")
    }

    @Test
    fun planetsAreNearTheEcliptic() {
        // Every planet stays within ~7° of the ecliptic; the Moon within ~5.3°.
        for (b in Planets.Body.entries) {
            val p = Planets.position(b, 1_760_000_000_000L)
            val ra = p.raHours * 15 * Math.PI / 180
            val dec = p.decDeg * Math.PI / 180
            val eps = 23.4393 * Math.PI / 180
            val sinBeta = kotlin.math.sin(dec) * kotlin.math.cos(eps) - kotlin.math.cos(dec) * kotlin.math.sin(eps) * kotlin.math.sin(ra)
            val beta = kotlin.math.asin(sinBeta) * 180 / Math.PI
            assertTrue(abs(beta) < 8, "$b beta=$beta")
        }
    }

    @Test
    fun precessionMatchesMeeusExample() {
        // Meeus, Astronomical Algorithms, ex. 21.b: theta Persei J2000 (2h44m11.986s, +49°13'42.48")
        // → 2028-11-13.19 TD: 2h46m11.331s, +49°20'54.54". Meeus also applies proper motion
        // (~1 s in RA, ~2.6" in Dec over 29 years), hence the tolerances.
        val j2000 = RaDec(2 + 44 / 60.0 + 11.986 / 3600, 49 + 13 / 60.0 + 42.48 / 3600)
        val epoch = 1_857_702_816_000L // 2028-11-13 04:33:36 UT
        val p = Astro.precessFromJ2000(j2000, epoch)
        assertTrue(abs(p.raHours - (2 + 46 / 60.0 + 11.331 / 3600)) < 2.0 / 3600, "ra=${p.raHours}")
        assertTrue(abs(p.decDeg - (49 + 20 / 60.0 + 54.54 / 3600)) < 5.0 / 3600, "dec=${p.decDeg}")
    }

    @Test
    fun catalogLoadsAndSearches() {
        val c = Catalog.loadDefault()
        assertTrue(c.objects.size > 500)
        val m42 = assertNotNull(c.find("M42"))
        assertTrue(abs(m42.fixed!!.raHours - 5.588) < 0.01)
        assertEquals("M42", c.search("m 42").first().id)
        // Forgiving search: bare numbers, no spaces, English names, small typos.
        assertEquals("M31", c.search("31").first().id)
        assertEquals("M31", c.search("ngc224").first().id)
        assertTrue(c.search("andro").take(3).any { it.id == "M31" })
        assertTrue(c.search("andromda").any { it.id == "M31" }, "typo")
        assertTrue(c.search("whirlpool").any { it.id == "M51" }, "English name")
        assertEquals("Júpiter", c.search("jupiter").first().id)
        assertTrue(c.search("Jupyter").any { it.id == "Júpiter" }, "typo in a planet")
        assertTrue(c.search("sirius").any { it.id == "Sirio" }, "English star name")
        assertTrue(c.search("orion").any { it.id == "M42" })
        assertNotNull(c.find("Júpiter"))
        assertNotNull(c.find("Vega"))
        assertTrue(c.alignmentStars.size > 50)
        assertTrue(c.find("Sol") == null)
    }

    @Test
    fun deepSkyBeyondMessier() {
        val c = Catalog.loadDefault()
        assertTrue(c.objects.count { it.category != org.starbridge.core.catalog.Category.STAR && it.body == null } > 1500)
        // Typed any way: "NGC 891", "ngc891", or by its Caldwell number.
        val ngc891 = assertNotNull(c.find("ngc891"))
        assertEquals("NGC 891", ngc891.id)
        assertEquals("NGC 891", c.search("C23").first().id)
        assertEquals("NGC 891", c.find("C23")?.id)
        // Big nebulae without a catalogued magnitude, with their Spanish names.
        assertEquals("Nebulosa Cabeza de Caballo", c.find("B 33")?.name)
        assertEquals("Doble Cúmulo de Perseo", c.find("C 14")?.name)
        assertTrue(c.search("velo").size >= 2)
        // NGC entries that are only stars stay out.
        assertTrue(c.objects.none { it.category == org.starbridge.core.catalog.Category.STAR && it.id.startsWith("NGC") })
    }

    @Test
    fun fullCatalogueStarsToMagnitude8AndEveryNgcIc() {
        val c = Catalog.loadDefault()
        val star = org.starbridge.core.catalog.Category.STAR
        assertTrue(c.objects.count { it.category == star } > 40_000, "stars to mag 8")
        assertTrue(c.objects.count { it.category != star && it.body == null } > 12_000, "the whole NGC/IC")
        // Every designation finds the star: HIP, HD, HR, Bayer, Flamsteed.
        for (q in listOf("hip 32349", "HD48915", "hr 2491", "α CMa", "9 CMa")) assertEquals("Sirio", c.search(q).first().id, q)
        assertTrue(c.search("61 cyg").first().id.startsWith("61 Cyg"))
        // Faint galaxies, by NGC or by UGC/PGC number.
        assertEquals("M31", c.search("pgc 2557").first().id)
        assertEquals("M31", c.search("UGC454").first().id)
        assertNotNull(c.find("NGC 2"), "a 14th-magnitude galaxy")
        // "31" still finds M31 first, among thousands of stars.
        assertEquals("M31", c.search("31").first().id)
    }

    @Test
    fun listsFollowTheTelescope() {
        val c = Catalog.loadDefault()
        val small = Catalog.deepSkyLimit(130, eaa = false)
        assertTrue(abs(small - 11.8) < 0.1, "130 mm → $small")
        assertTrue(Catalog.deepSkyLimit(203, eaa = false) > small + 0.9)
        assertTrue(Catalog.deepSkyLimit(130, eaa = true) > small + 1.4)
        val faint = assertNotNull(c.find("NGC 2"))
        assertTrue(!Catalog.listed(faint, small), "too faint for a 130 mm")
        assertTrue(Catalog.listed(assertNotNull(c.find("M31")), small))
        assertTrue(Catalog.listed(assertNotNull(c.find("B 33")), small), "no magnitude, but famous")
        assertTrue(c.objects.count { it.category != org.starbridge.core.catalog.Category.STAR && Catalog.listed(it, small) } in 1500..6000)
    }

    @Test
    fun regionOfTheSky() {
        val c = Catalog.loadDefault()
        val m31 = assertNotNull(c.find("M31"))
        val near = c.near(m31.fixed!!, 1.0).map { it.id }.toSet()
        assertTrue(near.containsAll(listOf("M31", "M32", "M110")), "$near")
        assertTrue("M42" !in near)
    }

    @Test
    fun searchStaysFast() {
        val c = Catalog.loadDefault()
        c.search("andromda") // warm up
        val t0 = System.nanoTime()
        repeat(5) { c.search("andromda"); c.search("ngc 7"); c.search("31") }
        val ms = (System.nanoTime() - t0) / 15 / 1e6
        assertTrue(ms < 250, "search took $ms ms")
    }
}
