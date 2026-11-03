package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Phase 17G-1: what a person asks Explore for, built only from closed lists. The words they typed are understood ON THE PHONE
 * ([ExploreQueryParser]) and never sent anywhere: only this structured query reaches the server.
 *
 * [categories] are alternatives (a place has one category), so they count as ONE thing asked for; each tag and the time of
 * day count as one more ([requested]) - the server counts the same way.
 */
data class ExploreQuery(
    val city: City?,
    val categories: List<Interest> = emptyList(),
    val facets: List<String> = emptyList(),
    val daypart: Daypart? = null
) {
    val requested: Int get() = (if (categories.isEmpty()) 0 else 1) + facets.size + (if (daypart == null) 0 else 1)

    /** Something was asked for (a city alone is not a request). */
    val isEmpty: Boolean get() = requested == 0

    /** Ready to send: a catalog city (never OTHER) and something asked for, within the limits. */
    val canSearch: Boolean
        get() = city != null && !city.isOther && !isEmpty && categories.size <= MAX_CATEGORIES &&
            facets.size <= ExploreFacets.MAX_PER_QUERY

    companion object {
        const val MAX_CATEGORIES = 2
    }
}

/**
 * One place Explore found. [matchedCategory] / [matchedFacets] / [matchedDaypart] say what matched; [matched] of
 * [requested] is "Matches 3 of the 4 things you asked for". Nothing says how many experiences or people stand behind it.
 */
data class ExploreHit(
    val placeId: String,
    val name: String,
    val category: Interest?,
    val city: City?,
    val matchedCategory: Boolean,
    val matchedFacets: List<String>,
    val matchedDaypart: Boolean,
    val requested: Int,
    val matched: Int
)

/** At most 20 places, best match first; the list always ends. Held in memory only (nothing about others is stored). */
data class ExploreResult(val city: City?, val requested: Int, val hits: List<ExploreHit>)

/** Phase 17G-1: Explore on the backend. No history, no cache: each search is one call, kept in memory by the screen. */
interface ExploreRepository {

    suspend fun search(query: ExploreQuery): ApiResult<ExploreResult>
}
