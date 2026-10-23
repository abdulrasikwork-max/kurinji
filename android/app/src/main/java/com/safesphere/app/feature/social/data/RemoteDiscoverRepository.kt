package com.safesphere.app.feature.social.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.ButterflyRequestDto
import com.safesphere.app.feature.social.data.remote.DiscoverApi
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CocoonPage
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostPage

/**
 * Phase 13C: butterflies, the Cocoon and Discover on the backend. All error handling is in [ApiCallHandler]; nothing is
 * cached. An answer the app cannot read (a DTO that fails closed to null) is treated as "not found".
 */
class RemoteDiscoverRepository(
    private val api: DiscoverApi,
    private val handler: ApiCallHandler
) : DiscoverRepository {

    override suspend fun setButterfly(postId: String, state: ButterflyState): ApiResult<ButterflyState> =
        orNotFound(handler.execute { api.setButterfly(postId, ButterflyRequestDto(state.name)).toDomain() })

    override suspend fun removeButterfly(postId: String): ApiResult<Unit> = handler.execute { api.removeButterfly(postId) }

    override suspend fun cocoon(state: ButterflyState, cursor: String?): ApiResult<CocoonPage> =
        handler.execute { api.cocoon(state.name, cursor).toDomain() }

    override suspend fun edition(city: City): ApiResult<Edition> =
        orNotFound(handler.execute { api.edition(city.name).toDomain() })

    override suspend fun fromConnections(cursor: String?): ApiResult<DiscoverPage> =
        handler.execute { api.fromConnections(cursor).toDomain() }

    override suspend fun categoryPage(category: Interest, city: City?, cursor: String?): ApiResult<PostPage> =
        handler.execute { api.categoryPage(category.name, city?.name, cursor).toDomain() }

    override suspend fun placePage(placeId: String, cursor: String?): ApiResult<PlacePage> =
        orNotFound(handler.execute { api.placePage(placeId, cursor).toDomain() })

    override suspend fun searchPlaces(query: String, city: City?): ApiResult<List<Place>> =
        handler.execute { api.searchPlaces(query.trim(), city?.name).mapNotNull { it.toDomain() } }

    private fun <T : Any> orNotFound(result: ApiResult<T?>): ApiResult<T> = when (result) {
        is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NOT_FOUND)
        is ApiResult.Failure -> result
    }
}
