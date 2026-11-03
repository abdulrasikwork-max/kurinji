package com.safesphere.app.feature.social.data.remote

import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.ExploreHit
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreResult
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.Understanding
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Phase 17G-1: Explore. From the ONE Retrofit instance (the JWT is attached). One POST whose body is the STRUCTURED query
 * (never the words a person typed: those are understood on the phone), answered with places.
 */
interface ExploreApi {

    @POST("api/explore/search")
    suspend fun search(@Body body: ExploreRequestDto): ExploreResponseDto

    /** Phase 17G-3 (optional): the ONLY request that carries the typed words, and only after the person agreed. */
    @POST("api/explore/understand")
    suspend fun understand(@Body body: UnderstandRequestDto): UnderstandResponseDto

    /** Phase 17G-3: whether to offer the switch at all. */
    @GET("api/explore/understanding")
    suspend fun understandingStatus(): UnderstandingStatusDto
}

/** The typed words (at most 200 characters). Never printed: [toString] shows only the length. */
data class UnderstandRequestDto(val text: String) {
    override fun toString() = "UnderstandRequestDto(text=<${text.length} chars>)"
}

/** The answer, read FAIL-CLOSED: unknown codes are dropped, limits (2 categories, 4 tags) are re-applied. */
data class UnderstandResponseDto(
    val categories: List<String>? = null,
    val facets: List<String>? = null,
    val daypart: String? = null
) {
    fun toDomain() = Understanding(
        categories = categories.orEmpty().mapNotNull(Interest::fromCode).distinct().take(ExploreQuery.MAX_CATEGORIES),
        facets = ExploreFacets.cleaned(facets).take(ExploreFacets.MAX_PER_QUERY),
        daypart = Daypart.entries.firstOrNull { it.name == daypart }
    )
}

data class UnderstandingStatusDto(val available: Boolean? = null)

/** The request body: closed-list codes only. */
data class ExploreRequestDto(
    val city: String,
    val categories: List<String>,
    val facets: List<String>,
    val daypart: String?
) {
    companion object {
        /** Only for a query that [ExploreQuery.canSearch]. */
        fun from(query: ExploreQuery) = ExploreRequestDto(
            city = requireNotNull(query.city).code,
            categories = query.categories.map { it.name },
            facets = query.facets,
            daypart = query.daypart?.name
        )
    }
}

data class ExplorePlaceDto(
    val publicId: String? = null,
    val name: String? = null,
    val category: String? = null,
    val city: String? = null
)

data class ExploreHitDto(
    val place: ExplorePlaceDto? = null,
    val matchedCategory: Boolean? = null,
    val matchedFacets: List<String>? = null,
    val matchedDaypart: Boolean? = null,
    val requested: Int? = null,
    val matched: Int? = null
) {
    /** A hit the app cannot read (no id or name) is dropped; an unknown tag is dropped; the numbers are kept sane. */
    fun toDomain(): ExploreHit? {
        val place = place ?: return null
        val id = place.publicId?.takeIf { it.isNotBlank() } ?: return null
        val name = place.name?.takeIf { it.isNotBlank() } ?: return null
        val asked = (requested ?: return null).coerceAtLeast(1)
        return ExploreHit(
            placeId = id,
            name = name,
            category = place.category?.let(Interest::fromCode),
            city = Cities.find(place.city),
            matchedCategory = matchedCategory == true,
            matchedFacets = ExploreFacets.cleaned(matchedFacets),
            matchedDaypart = matchedDaypart == true,
            requested = asked,
            matched = (matched ?: 0).coerceIn(0, asked)
        )
    }

    override fun toString() = "ExploreHitDto(matched=$matched, requested=$requested)"
}

data class ExploreResponseDto(
    val city: String? = null,
    val requested: Int? = null,
    val hits: List<ExploreHitDto>? = null
) {
    /** At most 20 places (the server's bound), each readable, each with at least one match. */
    fun toDomain() = ExploreResult(
        city = Cities.find(city),
        requested = (requested ?: 0).coerceAtLeast(0),
        hits = hits.orEmpty().mapNotNull { it.toDomain() }.filter { it.matched > 0 }.take(20)
    )
}
