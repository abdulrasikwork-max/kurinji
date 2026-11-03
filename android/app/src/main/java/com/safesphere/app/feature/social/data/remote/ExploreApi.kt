package com.safesphere.app.feature.social.data.remote

import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.ExploreHit
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreResult
import com.safesphere.app.feature.social.domain.Interest
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Phase 17G-1: Explore. From the ONE Retrofit instance (the JWT is attached). One POST whose body is the STRUCTURED query
 * (never the words a person typed: those are understood on the phone), answered with places.
 */
interface ExploreApi {

    @POST("api/explore/search")
    suspend fun search(@Body body: ExploreRequestDto): ExploreResponseDto
}

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
