package org.starbridge.core.catalog

import org.starbridge.core.mount.RaDec

/**
 * Constellation stick figures and labels for the sky map (J2000), from d3-celestial by Olaf
 * Frohn (BSD-3-Clause). Built by tools/build_constellations.mjs into constellations.tsv.
 */
class Constellations(val lines: List<Line>, val labels: List<Label>) {
    data class Line(val abbr: String, val points: List<RaDec>)
    data class Label(val abbr: String, val name: String, val position: RaDec)

    companion object {
        fun parse(text: String): Constellations {
            val lines = ArrayList<Line>()
            val labels = ArrayList<Label>()
            for (raw in text.lineSequence()) {
                if (raw.isBlank() || raw.startsWith("#")) continue
                val c = raw.split('\t')
                when (c[0]) {
                    "L" -> lines += Line(c[1], c[2].split(';').map { p ->
                        val (ra, dec) = p.split(',')
                        RaDec(ra.toDouble(), dec.toDouble())
                    })
                    "N" -> labels += Label(c[1], c[2], RaDec(c[3].toDouble(), c[4].toDouble()))
                }
            }
            return Constellations(lines, labels)
        }

        fun loadDefault(): Constellations = parse(
            requireNotNull(Constellations::class.java.classLoader.getResourceAsStream("constellations.tsv")) { "constellations.tsv missing" }
                .use { it.readBytes().decodeToString() },
        )
    }
}
