package com.safesphere.app.feature.social.domain

/** Phase 17G-1: one Explore tag with its English label and the Tamil / Tanglish names the word list is seeded from. */
data class ExploreFacet(val code: String, val english: String, val tamil: String, val tanglish: String)

/**
 * Phase 17G-1 (decision G-1): the Explore tags ("Good for...") from the ONE file `explore/explore-facets-v1.tsv`, bundled
 * byte-identical with the server's copy (the backend's ExploreFacetsParityTest compares the files and V29; this app's
 * Phase17GExploreTest checks the codes the app uses). A closed list: a tag the app does not know is dropped, never shown.
 * Pure apart from reading the bundled file once.
 */
object ExploreFacets {

    /** At most this many tags on one experience, and in one request. */
    const val MAX_PER_POST = 4
    const val MAX_PER_QUERY = 4

    private const val RESOURCE = "/explore/explore-facets-v1.tsv"

    /** Every tag, in the file's order. */
    val all: List<ExploreFacet> by lazy { load() }

    private val byCode: Map<String, ExploreFacet> by lazy { all.associateBy { it.code } }

    fun find(code: String?): ExploreFacet? = if (code == null) null else byCode[code]

    fun isValid(code: String?): Boolean = find(code) != null

    /** [codes] without repeats and without anything the list does not know, in the file's order. */
    fun cleaned(codes: List<String>?): List<String> {
        val wanted = codes.orEmpty().toSet()
        return all.map { it.code }.filter { it in wanted }
    }

    /** Parses the file's text (also used by tests). */
    fun parse(text: String): List<ExploreFacet> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val f = line.split('\t')
            require(f.size == 4) { "A tag line needs 4 fields" }
            ExploreFacet(code = f[0], english = f[1], tamil = f[2], tanglish = f[3])
        }.toList()

    private fun load(): List<ExploreFacet> {
        val stream = ExploreFacets::class.java.getResourceAsStream(RESOURCE) ?: return emptyList()
        return stream.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    }
}
