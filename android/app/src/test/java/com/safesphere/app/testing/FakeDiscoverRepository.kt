package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostPage

/** Test double (Phase 13C): an in-memory Discover server that records what the phone asked for. */
class FakeDiscoverRepository : DiscoverRepository {

    val editionCities = mutableListOf<City>()
    val connectionCursors = mutableListOf<String?>()
    val categoryRequests = mutableListOf<Triple<Interest, City?, String?>>()
    val placeRequests = mutableListOf<Pair<String, String?>>()
    val searches = mutableListOf<Pair<String, City?>>()

    var edition: ApiResult<Edition>? = null
    var connectionPages: Map<String?, DiscoverPage> = mapOf(null to DiscoverPage(emptyList(), null))
    var categoryPages: Map<String?, PostPage> = mapOf(null to PostPage(emptyList(), null))
    var placePages: Map<String?, PlacePage> = emptyMap()
    var places: List<Place> = emptyList()
    var searchFailure: ApiResult.Failure? = null

    override suspend fun edition(city: City): ApiResult<Edition> {
        editionCities += city
        return edition ?: ApiResult.Success(Edition(city, null, null, emptyList()))
    }

    override suspend fun fromConnections(cursor: String?): ApiResult<DiscoverPage> {
        connectionCursors += cursor
        return connectionPages[cursor]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.UNKNOWN)
    }

    override suspend fun categoryPage(category: Interest, city: City?, cursor: String?): ApiResult<PostPage> {
        categoryRequests += Triple(category, city, cursor)
        return categoryPages[cursor]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.UNKNOWN)
    }

    override suspend fun placePage(placeId: String, cursor: String?): ApiResult<PlacePage> {
        placeRequests += placeId to cursor
        return placePages[cursor]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NOT_FOUND)
    }

    override suspend fun searchPlaces(query: String, city: City?): ApiResult<List<Place>> {
        searches += query to city
        return searchFailure ?: ApiResult.Success(places)
    }
}
