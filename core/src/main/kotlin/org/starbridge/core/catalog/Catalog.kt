package org.starbridge.core.catalog

import org.starbridge.core.astro.Astro
import org.starbridge.core.astro.Planets
import org.starbridge.core.astro.Site
import org.starbridge.core.mount.RaDec

enum class Category(val label: String) {
    PLANET("Planeta"), MOON("Luna"), STAR("Estrella"), GALAXY("Galaxia"), NEBULA("Nebulosa"),
    CLUSTER("Cúmulo"), OTHER("Otro");

    companion object {
        fun fromOpenNgcType(type: String): Category = when {
            type.contains("Galax") -> GALAXY
            type.contains("cluster", ignoreCase = true) && !type.contains("nebula", ignoreCase = true) -> CLUSTER
            type.contains("Asterism") -> CLUSTER
            type.contains("nebula", ignoreCase = true) || type.contains("remnant") -> NEBULA
            type.contains("star", ignoreCase = true) -> STAR
            else -> OTHER
        }
    }
}

data class CatalogObject(
    val id: String,
    val name: String,
    /** Human readable type, e.g. "Galaxia", "Cúmulo globular". */
    val type: String,
    val category: Category,
    /** J2000 coordinates. Null for solar-system bodies (computed at request time). */
    val fixed: RaDec?,
    val magnitude: Double?,
    val constellation: String,
    val body: Planets.Body? = null,
    val messier: Boolean = false,
    /** Bright named star suitable for alignment. */
    val alignmentStar: Boolean = false,
    /** Other catalogue numbers it is known by, e.g. "C14" (Caldwell). Searchable. */
    val aliases: List<String> = emptyList(),
    /** Other names, only for searching (the English name: "Andromeda Galaxy", "Jupiter"). */
    val otherNames: List<String> = emptyList(),
) {
    /** Position in the equinox of date, as the hand control expects. */
    fun position(epochMillis: Long, site: Site): RaDec =
        fixed?.let { Astro.precessFromJ2000(it, epochMillis) }
            ?: Planets.topocentric(requireNotNull(body), epochMillis, site)

    val displayName: String get() = if (name.isNotBlank() && name != id) "$id · $name" else id
}

/**
 * Embedded offline catalog:
 * - Moon and planets (computed),
 * - 925 stars to magnitude 4.5 from HYG v4 (CC BY-SA 4.0), named ones searchable,
 * - ~1860 deep-sky objects from OpenNGC (CC BY-SA 4.0): all Messier and Caldwell, NGC/IC to
 *   magnitude 12 and the big named nebulae (tools/build_catalog.py).
 */
class Catalog(val objects: List<CatalogObject>) {
    private val byId = objects.associateBy { it.id.lowercase() }
    /** "ngc869", "c14": typed without spaces or by another catalogue number. */
    private val byKey = HashMap<String, CatalogObject>().apply {
        objects.forEach { o -> (o.aliases + o.id).forEach { putIfAbsent(normalize(it), o) } }
    }

    fun find(id: String): CatalogObject? = byId[id.lowercase()] ?: byKey[normalize(id)]

    /**
     * Forgiving search, best matches first:
     * - a bare number finds that catalogue number: "31" → M31 (then NGC 31, IC 31, C31…);
     * - ids and other catalogue numbers with or without spaces: "m 42", "ngc224", "c14";
     * - any word of the name, in Spanish or English, from its start or inside it: "andro",
     *   "remolino", "whirlpool", "orion";
     * - small typos in names: "andromda", "betelguese", "jupyter".
     */
    fun search(query: String, limit: Int = 40, category: Category? = null): List<CatalogObject> {
        val q = normalize(query)
        val pool = objects.asSequence().filter { category == null || it.category == category }
        if (q.isEmpty()) {
            return pool.filter { it.category != Category.STAR || it.alignmentStar }.take(limit).toList()
        }
        val number = q.toIntOrNull()?.toString()
        return pool.mapNotNull { o -> score(o, q, number)?.let { o to it } }
            .sortedWith(compareBy<Pair<CatalogObject, Int>> { it.second }.thenBy { rank(it.first) }.thenBy { it.first.magnitude ?: -30.0 })
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Lower is better; null = no match. */
    private fun score(o: CatalogObject, q: String, number: String?): Int? {
        val keys = (o.aliases + o.id + o.otherNames.filter { CATALOG_NUMBER.matches(normalize(it)) }).map(::normalize)
        if (keys.any { it == q }) return 0
        if (number != null) {
            // "31": the number of a catalogue id ("m31", "ngc31", "c31", "hip31" never).
            val nums = keys.mapNotNull { k -> CATALOG_NUMBER.matchEntire(k)?.let { it.groupValues[1] to it.groupValues[2] } }
            nums.firstOrNull { it.second == number }?.let { (cat, _) -> return if (cat == "m") 1 else if (cat == "c") 2 else 3 }
        }
        if (keys.any { it.startsWith(q) }) return 4
        val names = (listOf(o.name, o.id) + o.otherNames).filter { it.isNotBlank() }
        val words = names.flatMap { n -> n.split(WORD_SPLIT).map(::normalize).filter { it.isNotEmpty() } }
        if (words.any { it.startsWith(q) }) return 5
        if (names.any { normalize(it).contains(q) }) return 6
        if (q.length >= 4 && words.any { w -> w.length >= 4 && editDistance(w.take(q.length + 1), q) <= if (q.length >= 7) 2 else 1 }) return 7
        return null
    }

    /** Within the same score: the Moon and planets, Messier, other deep sky, then stars. */
    private fun rank(o: CatalogObject) = when {
        o.body != null -> 0
        o.messier -> 1
        o.category != Category.STAR -> 2
        o.alignmentStar -> 3
        else -> 4
    }

    val alignmentStars: List<CatalogObject> get() = objects.filter { it.alignmentStar }

    companion object {
        private fun normalize(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[\\s\\-·.'’_]"), "")

        private val WORD_SPLIT = Regex("[\\s/·,()'’-]+")
        /** "m31", "ngc7000", "ic434", "c14" → catalogue and number. */
        private val CATALOG_NUMBER = Regex("(m|ngc|ic|c)(\\d+)")

        /** Levenshtein distance (short strings: names and queries). */
        private fun editDistance(a: String, b: String): Int {
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                cur.copyInto(prev)
            }
            return prev[b.length]
        }

        /** OpenNGC deep-sky objects (catalog.tsv). */
        fun parseDeepSky(text: String): List<CatalogObject> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val c = line.split('\t')
                CatalogObject(
                    id = c[0],
                    name = c[1].substringBefore(" / ")
                        .let { if (it.startsWith("NGC") || it.startsWith("IC")) "" else it }
                        .let { SPANISH_NAMES[it] ?: it },
                    // Every name and number OpenNGC gives ("Andromeda Galaxy", "NGC 224").
                    otherNames = c[1].split(" / ").map { it.trim() }.filter { it.isNotEmpty() },
                    type = spanishType(c[2]),
                    category = Category.fromOpenNgcType(c[2]),
                    fixed = RaDec(c[3].toDouble(), c[4].toDouble()),
                    magnitude = c[5].toDoubleOrNull(),
                    constellation = c.getOrElse(6) { "" },
                    messier = c[0].matches(Regex("M\\d+")),
                    aliases = c.getOrElse(7) { "" }.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                )
            }.toList()

        /** HYG stars (stars.tsv): hip, name, bayer, const, ra, dec, mag. */
        fun parseStars(text: String): List<CatalogObject> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val c = line.split('\t')
                val name = c[1]
                val bayer = c[2]
                val mag = c[6].toDouble()
                CatalogObject(
                    id = name.ifBlank { bayer.ifBlank { "HIP ${c[0]}" } },
                    name = if (name.isNotBlank()) bayer else "",
                    type = "Estrella",
                    category = Category.STAR,
                    fixed = RaDec(c[4].toDouble(), c[5].toDouble()),
                    magnitude = mag,
                    constellation = c[3],
                    alignmentStar = name.isNotBlank() && mag <= 2.6,
                )
            }.toList()

        fun solarSystem(): List<CatalogObject> = Planets.Body.entries
            .filter { it != Planets.Body.SUN } // never offer a GoTo to the Sun
            .map {
                val spanish = when (it) {
                    Planets.Body.MOON -> "Luna"
                    Planets.Body.MERCURY -> "Mercurio"
                    Planets.Body.VENUS -> "Venus"
                    Planets.Body.MARS -> "Marte"
                    Planets.Body.JUPITER -> "Júpiter"
                    Planets.Body.SATURN -> "Saturno"
                    Planets.Body.URANUS -> "Urano"
                    Planets.Body.NEPTUNE -> "Neptuno"
                    Planets.Body.SUN -> "Sol"
                }
                CatalogObject(
                    id = spanish, name = "", type = if (it == Planets.Body.MOON) "Satélite" else "Planeta",
                    otherNames = listOf(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }),
                    category = if (it == Planets.Body.MOON) Category.MOON else Category.PLANET,
                    fixed = null, magnitude = null, constellation = "", body = it,
                )
            }

        /** Spanish names for the best-known objects (OpenNGC names are in English). */
        private val SPANISH_NAMES = mapOf(
            "Crab Nebula" to "Nebulosa del Cangrejo",
            "Butterfly Cluster" to "Cúmulo de la Mariposa",
            "Ptolemy's Cluster" to "Cúmulo de Ptolomeo",
            "Lagoon Nebula" to "Nebulosa de la Laguna",
            "Amas de l'Ecu de Sobieski" to "Cúmulo del Pato Salvaje",
            "Hercules Globular Cluster" to "Gran Cúmulo de Hércules",
            "Eagle Nebula" to "Nebulosa del Águila",
            "Checkmark Nebula" to "Nebulosa Omega",
            "Trifid Nebula" to "Nebulosa Trífida",
            "Small Sgr Star Cloud" to "Pequeña Nube Estelar de Sagitario",
            "Dumbbell Nebula" to "Nebulosa Dumbbell",
            "Andromeda Galaxy" to "Galaxia de Andrómeda",
            "Triangulum Galaxy" to "Galaxia del Triángulo",
            "Great Orion Nebula" to "Gran Nebulosa de Orión",
            "Mairan's Nebula" to "Nebulosa de Mairan",
            "Beehive" to "El Pesebre",
            "Pleiades" to "Pléyades",
            "Whirlpool Galaxy" to "Galaxia del Remolino",
            "Ring Nebula" to "Nebulosa del Anillo",
            "Sunflower Galaxy" to "Galaxia del Girasol",
            "Black Eye Galaxy" to "Galaxia del Ojo Negro",
            "Barbell Nebula" to "Pequeña Dumbbell",
            "Bode's Galaxy" to "Galaxia de Bode",
            "Cigar Galaxy" to "Galaxia del Cigarro",
            "Southern Pinwheel Galaxy" to "Molinete Austral",
            "Virgo Galaxy" to "Virgo A",
            "Owl Nebula" to "Nebulosa del Búho",
            "Coma Pinwheel" to "Molinete de Coma",
            "Sombrero Galaxy" to "Galaxia del Sombrero",
            "Brocchi's Cluster" to "La Percha",
            "Large Magellanic Cloud" to "Gran Nube de Magallanes",
            "Small Magellanic Cloud" to "Pequeña Nube de Magallanes",
            "Flaming Star Nebula" to "Nebulosa de la Estrella Llameante",
            "Pelican Nebula" to "Nebulosa del Pelícano",
            "Cocoon Nebula" to "Nebulosa del Capullo",
            "Owl Cluster" to "Cúmulo del Búho",
            "h Persei Cluster" to "Doble Cúmulo de Perseo (h)",
            "chi Persei Cluster" to "Doble Cúmulo de Perseo (χ)",
            "California Nebula" to "Nebulosa California",
            "Rosette Nebula" to "Nebulosa Roseta",
            "Christmas Tree Cluster" to "Cúmulo del Árbol de Navidad",
            "Eskimo Nebula" to "Nebulosa del Esquimal",
            "Jupiter's Ghost Nebula" to "Fantasma de Júpiter",
            "Carina Nebula" to "Nebulosa de Carina",
            "Whale Galaxy" to "Galaxia de la Ballena",
            "Cat's Eye Nebula" to "Nebulosa Ojo de Gato",
            "Blinking Planetary" to "Planetaria Parpadeante",
            "Crescent Nebula" to "Nebulosa Creciente",
            "Fireworks Galaxy" to "Galaxia de los Fuegos Artificiales",
            "Veil Nebula" to "Nebulosa del Velo",
            "North America Nebula" to "Nebulosa Norteamérica",
            "Helix Nebula" to "Nebulosa de la Hélice",
            "Saturn Nebula" to "Nebulosa de Saturno",
            "Blue Snowball" to "Bola de Nieve Azul",
            "Sculptor Galaxy" to "Galaxia del Escultor",
            "Copeland's Blue Snowball" to "Bola de Nieve Azul",
            "Rosette A" to "Nebulosa Roseta",
            "Rosette B" to "Nebulosa Roseta (B)",
            "Horsehead Nebula" to "Nebulosa Cabeza de Caballo",
            "Flame Nebula" to "Nebulosa de la Llama",
            "Bubble Nebula" to "Nebulosa de la Burbuja",
            "Cave Nebula" to "Nebulosa de la Cueva",
            "Iris Nebula" to "Nebulosa del Iris",
            "Eastern Veil" to "Velo Oriental",
            "Witch Head Nebula" to "Nebulosa Cabeza de Bruja",
            "Running Man Nebula" to "Nebulosa del Hombre que Corre",
            "Monkey Head Nebula" to "Nebulosa Cabeza de Mono",
            "Pencil Nebula" to "Nebulosa del Lápiz",
            "Merope Nebula" to "Nebulosa de Mérope",
            "Maia Nebula" to "Nebulosa de Maia",
            "Hind's Nebula" to "Nebulosa de Hind",
            "Hubble's Nebula" to "Nebulosa de Hubble",
            "Bear Claw Nebula" to "Nebulosa Garra de Oso",
            "Red Spider Nebula" to "Nebulosa Araña Roja",
            "Bug Nebula" to "Nebulosa del Insecto",
            "Bow-Tie nebula" to "Nebulosa Pajarita",
            "Eight-Burst Nebula" to "Nebulosa de los Ocho Estallidos",
            "Little Ghost Nebula" to "Nebulosa del Pequeño Fantasma",
            "Blue Planetary" to "Planetaria Azul",
            "Coalsack Nebula" to "Saco de Carbón",
            "Double Cluster" to "Doble Cúmulo de Perseo",
            "Hyades" to "Híades",
            "Coma Star Cluster" to "Cúmulo de Coma",
            "Herschel's Jewel Box" to "Joyero de Herschel",
            "Wishing Well Cluster" to "Cúmulo del Pozo de los Deseos",
            "Pearl Cluster" to "Cúmulo de la Perla",
            "Caroline's Cluster" to "Cúmulo de Caroline",
            "Foxhead Cluster" to "Cúmulo Cabeza de Zorro",
            "Needle Galaxy" to "Galaxia de la Aguja",
            "Spindle Galaxy" to "Galaxia del Huso",
            "Umbrella Galaxy" to "Galaxia del Paraguas",
            "Cocoon Galaxy" to "Galaxia del Capullo",
            "Butterfly Galaxies" to "Galaxias Mariposa",
            "Antennae Galaxies" to "Galaxias Antena",
            "Mice Galaxy" to "Galaxias Ratones",
            "Stephan's Quintet" to "Quinteto de Stephan",
            "Seyfert's Sextet" to "Sexteto de Seyfert",
            "Barnard's Galaxy" to "Galaxia de Barnard",
            "Eyes" to "Los Ojos",
        )

        private fun spanishType(t: String) = when (t) {
            "Open cluster" -> "Cúmulo abierto"
            "Globular cluster" -> "Cúmulo globular"
            "Galaxy" -> "Galaxia"
            "Galaxy pair" -> "Par de galaxias"
            "Galaxy triplet" -> "Trío de galaxias"
            "Galaxy group" -> "Grupo de galaxias"
            "Planetary nebula" -> "Nebulosa planetaria"
            "Emission nebula" -> "Nebulosa de emisión"
            "Reflection nebula" -> "Nebulosa de reflexión"
            "Dark nebula" -> "Nebulosa oscura"
            "Nebula" -> "Nebulosa"
            "Supernova remnant" -> "Resto de supernova"
            "Cluster + nebula" -> "Cúmulo con nebulosa"
            "Asterism" -> "Asterismo"
            "Double star" -> "Estrella doble"
            "Star" -> "Estrella"
            else -> t
        }

        private fun resource(name: String): String =
            Catalog::class.java.getResourceAsStream("/$name")?.bufferedReader()?.use { it.readText() }
                ?: error("$name not found on classpath")

        /** Loads the bundled catalogs from the classpath (works on JVM and Android). */
        fun loadDefault(): Catalog {
            // English star names (Sirius, Betelgeuse…) are searchable too.
            val starEnglish = resource("star_names_en.tsv").lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('	') }.filter { it.size >= 2 }.associate { it[0] to it[1] }
            val stars = parseStars(resource("stars.tsv")).map { s -> starEnglish[s.id]?.let { s.copy(otherNames = listOf(it)) } ?: s }
            val all = solarSystem() + parseDeepSky(resource("catalog.tsv")) + stars
            // Ids must be unique: suffix repeats ("π Ori", "π Ori (2)").
            val seen = HashMap<String, Int>()
            return Catalog(all.map { o ->
                val n = seen.merge(o.id.lowercase(), 1, Int::plus) ?: 1
                if (n == 1) o else o.copy(id = "${o.id} ($n)")
            })
        }
    }
}
