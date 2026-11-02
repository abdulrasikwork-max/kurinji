package com.safesphere.app.feature.social.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.DiscoverApi
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.SavedPage
import com.safesphere.app.feature.social.domain.DiscoverPage
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Edition
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PlacePage
import com.safesphere.app.feature.social.domain.PostPage

/**
 * Phase 13C: Discover on the backend; Phase 17E: reactions and Saved. All error handling is in [ApiCallHandler]; nothing is
 * cached. An answer the app cannot read (a DTO that fails closed to null) is treated as "not found".
 */
class RemoteDiscoverRepository(
    private val api: DiscoverApi,
    private val handler: ApiCallHandler
) : DiscoverRepository {

    override suspend fun react(postId: String): ApiResult<Unit> = handler.execute { api.react(postId); Unit }

    override suspend fun unreact(postId: String): ApiResult<Unit> = handler.execute { api.unreact(postId) }

    override suspend fun save(postId: String): ApiResult<Unit> = handler.execute { api.save(postId); Unit }

    override suspend fun unsave(postId: String): ApiResult<Unit> = handler.execute { api.unsave(postId) }

    override suspend fun saved(cursor: String?): ApiResult<SavedPage> =
        handler.execute { api.saved(cursor).toDomain() }

    override suspend fun edition(city: City): ApiResult<Edition> =
        orNotFound(handler.execute { api.edition(city.code).toDomain() })

    override suspend fun fromConnections(cursor: String?): ApiResult<DiscoverPage> =
        handler.execute { api.fromConnections(cursor).toDomain() }

    override suspend fun categoryPage(category: Interest, city: City?, cursor: String?): ApiResult<PostPage> =
        handler.execute { api.categoryPage(category.name, city?.code, cursor).toDomain() }

    override suspend fun placePage(placeId: String, cursor: String?): ApiResult<PlacePage> =
        orNotFound(handler.execute { api.placePage(placeId, cursor).toDomain() })

    override suspend fun searchPlaces(query: String, city: City?): ApiResult<List<Place>> =
        handler.execute { api.searchPlaces(query.trim(), city?.code).mapNotNull { it.toDomain() } }

    private fun <T : Any> orNotFound(result: ApiResult<T?>): ApiResult<T> = when (result) {
        is ApiResult.Success -> result.data?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NOT_FOUND)
        is ApiResult.Failure -> result
    }
}
