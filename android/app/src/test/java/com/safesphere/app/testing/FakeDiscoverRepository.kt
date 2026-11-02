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
import com.safesphere.app.feature.social.domain.SavedItem
import com.safesphere.app.feature.social.domain.SavedPage
import com.safesphere.app.feature.social.domain.TimeBand

/** Test double (Phase 13C, 17E): an in-memory Discover server that records what the phone asked for. */
class FakeDiscoverRepository : DiscoverRepository {

    /** Phase 17E: every mark call, as "react:<id>", "unreact:<id>", "save:<id>", "unsave:<id>". */
    val markCalls = mutableListOf<String>()
    val editionCities = mutableListOf<City>()
    val connectionCursors = mutableListOf<String?>()
    val savedCursors = mutableListOf<String?>()
    val categoryRequests = mutableListOf<Triple<Interest, City?, String?>>()
    val placeRequests = mutableListOf<Pair<String, String?>>()
    val searches = mutableListOf<Pair<String, City?>>()

    /** The answer of every mark call (react, unreact, save, unsave). */
    var markResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var edition: ApiResult<Edition>? = null
    var connectionPages: Map<String?, DiscoverPage> = mapOf(null to DiscoverPage(emptyList(), null))
    var savedPages: Map<String?, SavedPage> = emptyMap()
    var savedFailure: ApiResult.Failure? = null
    var categoryPages: Map<String?, PostPage> = mapOf(null to PostPage(emptyList(), null))
    var placePages: Map<String?, PlacePage> = emptyMap()
    var places: List<Place> = emptyList()
    var searchFailure: ApiResult.Failure? = null

    override suspend fun react(postId: String): ApiResult<Unit> = mark("react:$postId")

    override suspend fun unreact(postId: String): ApiResult<Unit> = mark("unreact:$postId")

    override suspend fun save(postId: String): ApiResult<Unit> = mark("save:$postId")

    override suspend fun unsave(postId: String): ApiResult<Unit> = mark("unsave:$postId")

    private fun mark(call: String): ApiResult<Unit> {
        markCalls += call
        return markResult
    }

    override suspend fun saved(cursor: String?): ApiResult<SavedPage> {
        savedCursors += cursor
        savedFailure?.let { return it }
        return ApiResult.Success(savedPages[cursor] ?: SavedPage(emptyList(), null))
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
        return searchFailure ?: ApiResult.Success(places)
    }

    companion object {
        fun savedItem(id: String, band: TimeBand = TimeBand.THIS_WEEK) =
            SavedItem(FakeExperienceRepository.post(id = id), band)
    }
}
