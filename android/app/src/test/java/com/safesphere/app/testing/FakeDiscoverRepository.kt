package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CocoonItem
import com.safesphere.app.feature.social.domain.CocoonPage
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostPage
import com.safesphere.app.feature.social.domain.TimeBand

/** Test double (Phase 13C): an in-memory Discover server that records what the phone asked for. */
class FakeDiscoverRepository : DiscoverRepository {

    val butterflyCalls = mutableListOf<Pair<String, ButterflyState?>>() // null state = removed
    val editionCities = mutableListOf<City>()
    val connectionCursors = mutableListOf<String?>()
    val cocoonRequests = mutableListOf<Pair<ButterflyState, String?>>()
    val categoryRequests = mutableListOf<Triple<Interest, City?, String?>>()
    val placeRequests = mutableListOf<Pair<String, String?>>()
    val searches = mutableListOf<Pair<String, City?>>()

    var butterflyResult: ApiResult<ButterflyState>? = null
    var removeResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var edition: ApiResult<Edition>? = null
    var connectionPages: Map<String?, DiscoverPage> = mapOf(null to DiscoverPage(emptyList(), null))
    var cocoonPages: Map<Pair<ButterflyState, String?>, CocoonPage> = emptyMap()
    var categoryPages: Map<String?, PostPage> = mapOf(null to PostPage(emptyList(), null))
    var placePages: Map<String?, PlacePage> = emptyMap()
    var places: List<Place> = emptyList()

    override suspend fun setButterfly(postId: String, state: ButterflyState): ApiResult<ButterflyState> {
        butterflyCalls += postId to state
        return butterflyResult ?: ApiResult.Success(state)
    }

    override suspend fun removeButterfly(postId: String): ApiResult<Unit> {
        butterflyCalls += postId to null
        return removeResult
    }

    override suspend fun cocoon(state: ButterflyState, cursor: String?): ApiResult<CocoonPage> {
        cocoonRequests += state to cursor
        return ApiResult.Success(cocoonPages[state to cursor] ?: CocoonPage(emptyList(), null))
    }

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
        return ApiResult.Success(places)
    }

    companion object {
        fun cocoonItem(id: String, state: ButterflyState = ButterflyState.WANT) =
            CocoonItem(FakeExperienceRepository.post(id = id), state, TimeBand.THIS_WEEK)
    }
}
