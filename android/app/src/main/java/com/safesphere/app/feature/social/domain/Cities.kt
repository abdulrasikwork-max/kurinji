package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.location.GeoHash
import java.text.Normalizer
import java.util.Locale

/** Phase 17E (D-5): a city's approximate box, in degrees (the same numbers as the server). */
data class CityBox(val latMin: Double, val latMax: Double, val lngMin: Double, val lngMax: Double) {
    fun holds(lat: Double, lng: Double) = lat in latMin..latMax && lng in lngMin..lngMax
}

/**
 * Phase 17E (D-5): a city Kurinji knows, from the ONE catalog file `cities/cities-v1.tsv` (bundled byte-identical with
 * the server's copy; the backend's CityCatalogTest compares them). [OTHER] is the post-only "another city".
 */
data class City(
    val code: String,
    val name: String,
    val state: String?,
    val box: CityBox?,
    val aliases: List<String> = emptyList()
) {
    val isOther: Boolean get() = code == OTHER_CODE

    companion object {
        const val OTHER_CODE = "OTHER"
    }
}

/**
 * Phase 17E (D-5): the city catalog on the phone. Search runs here, on the bundled list, so no search words ever leave
 * the phone; an area post says "Around Chennai" from its own 6-character area, also computed here. Replaces 13C's
 * CityMatch and 16's CityAreas. Results are ordered by how well the words match and then by name, never by how
 * popular a city is. Pure apart from reading the bundled file once.
 */
object Cities {

    private const val RESOURCE = "/cities/cities-v1.tsv"

    /** The post-only "another city" (no box, never a list city). */
    val other: City = City(City.OTHER_CODE, "Another city", null, null)

    /** Every catalog city in the catalog's order (Chennai first: the launch city). */
    val all: List<City> by lazy { load() }

    private val byCode: Map<String, City> by lazy { all.associateBy { it.code } }

    /** The city with [code] (also OTHER), or null for an unknown code (fail closed: never guessed). */
    fun find(code: String?): City? {
        if (code == null) return null
        if (code == City.OTHER_CODE) return other
        return byCode[code]
    }

    /** A city an Edition or a list can be made for (never OTHER). */
    fun listCity(code: String?): City? = find(code)?.takeUnless { it.isOther }

    /** The city whose box holds the centre of [cell], or null (also for an invalid cell). */
    fun cityOf(cell: String?): City? {
        if (cell.isNullOrEmpty()) return null
        val centre = runCatching { GeoHash.centre(cell) }.getOrNull() ?: return null
        return all.firstOrNull { it.box?.holds(centre.latitude, centre.longitude) == true }
    }

    /**
     * The city a profile's free-text city names (its name or a common other name: "Madras", "Bangalore", "Trichy"),
     * comparing letters only, case- and accent-insensitive; null for blank, unknown or "another city".
     */
    fun match(text: String?): City? {
        if (text.isNullOrBlank()) return null
        val key = key(text)
        if (key.isEmpty()) return null
        return all.firstOrNull { city -> key(city.name) == key || city.aliases.any { key(it) == key } }
    }

    /**
     * Cities whose name, other names or state match [query]: names that start with it first, then other names that
     * start with it, then anything that contains it; alphabetical within each group. A blank query gives the catalog in
     * its own order. At most [limit].
     */
    fun search(query: String, limit: Int = 30): List<City> {
        val q = key(query)
        if (q.isEmpty()) return all.take(limit)
        fun rank(city: City): Int? {
            val name = key(city.name)
            val aliases = city.aliases.map(::key)
            return when {
                name.startsWith(q) -> 0
                aliases.any { it.startsWith(q) } -> 1
                name.contains(q) || aliases.any { it.contains(q) } -> 2
                city.state != null && key(city.state).startsWith(q) -> 3
                else -> null
            }
        }
        return all.mapNotNull { city -> rank(city)?.let { it to city } }
            .sortedWith(compareBy<Pair<Int, City>>({ it.first }, { it.second.name }))
            .map { it.second }.take(limit)
    }

    /** Parses the catalog file's text (also used by tests). */
    fun parse(text: String): List<City> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val f = line.split('\t')
            require(f.size == 8) { "A catalog line needs 8 fields" }
            City(
                code = f[0], name = f[1], state = f[2],
                box = CityBox(f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble()),
                aliases = f[7].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            )
        }.toList()

    private fun load(): List<City> {
        val stream = Cities::class.java.getResourceAsStream(RESOURCE) ?: return emptyList()
        return stream.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    }

    private fun key(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
}
