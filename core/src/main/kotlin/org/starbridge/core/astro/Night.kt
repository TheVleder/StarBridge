package org.starbridge.core.astro

import org.starbridge.core.catalog.CatalogObject

/**
 * How an object does over the next hours: its altitude curve against the Sun's, when it rises,
 * culminates and sets, and when the sky is properly dark. For the object page of the planner.
 */
data class NightInfo(
    /** Every [Night.STEP_MS] from [Night.BEFORE_MS] before now: (epoch ms, object alt, Sun alt). */
    val samples: List<Triple<Long, Double, Double>>,
    val rise: Long?,
    val transit: Long?,
    val set: Long?,
    val maxAltDeg: Double,
    /** Astronomical darkness (Sun below −18°) in the window, if any. */
    val darkStart: Long?,
    val darkEnd: Long?,
    /** Best moment: highest point while dark (or overall, if it never gets dark). */
    val best: Long?,
)

object Night {
    const val STEP_MS = 10 * 60_000L
    const val BEFORE_MS = 60 * 60_000L
    const val AFTER_MS = 16 * 60 * 60_000L
    const val DARK_SUN_DEG = -18.0

    fun of(obj: CatalogObject, site: Site, now: Long): NightInfo {
        val times = generateSequence(now - BEFORE_MS) { it + STEP_MS }.takeWhile { it <= now + AFTER_MS }.toList()
        val samples = times.map { t ->
            val alt = Astro.apparentAltAz(obj.position(t, site), site, t).altDeg
            val sun = Astro.apparentAltAz(Planets.topocentric(Planets.Body.SUN, t, site), site, t).altDeg
            Triple(t, alt, sun)
        }
        fun crossing(i: Int, a: Double, b: Double, level: Double): Long {
            val f = if (b == a) 0.0 else (level - a) / (b - a)
            return samples[i].first + (f * STEP_MS).toLong()
        }
        var rise: Long? = null
        var set: Long? = null
        var darkStart: Long? = null
        var darkEnd: Long? = null
        for (i in 0 until samples.size - 1) {
            val (_, a0, s0) = samples[i]
            val (_, a1, s1) = samples[i + 1]
            if (rise == null && a0 < 0 && a1 >= 0 && samples[i + 1].first > now) rise = crossing(i, a0, a1, 0.0)
            if (set == null && a0 >= 0 && a1 < 0 && samples[i + 1].first > now) set = crossing(i, a0, a1, 0.0)
            if (darkStart == null && s0 > DARK_SUN_DEG && s1 <= DARK_SUN_DEG) darkStart = crossing(i, s0, s1, DARK_SUN_DEG)
            if (darkEnd == null && s0 <= DARK_SUN_DEG && s1 > DARK_SUN_DEG && samples[i + 1].first > now) darkEnd = crossing(i, s0, s1, DARK_SUN_DEG)
        }
        // Already dark at the start of the window.
        if (darkStart == null && samples.first().third <= DARK_SUN_DEG) darkStart = samples.first().first
        val ahead = samples.filter { it.first >= now }
        val top = ahead.maxByOrNull { it.second }
        val transit = top?.takeIf { it != ahead.first() && it != ahead.last() }?.first
        val dark = ahead.filter { it.third <= DARK_SUN_DEG && it.second > 0 }
        val best = (dark.maxByOrNull { it.second } ?: top?.takeIf { it.second > 0 })?.first
        return NightInfo(samples, rise, transit, set, top?.second ?: samples.maxOf { it.second }, darkStart, darkEnd, best)
    }
}
