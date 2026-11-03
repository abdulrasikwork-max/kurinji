package com.safesphere.app.feature.social.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.ExploreApi
import com.safesphere.app.feature.social.data.remote.ExploreRequestDto
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreRepository
import com.safesphere.app.feature.social.domain.ExploreResult

/**
 * Phase 17G-1: Explore on the backend. All error handling is in [ApiCallHandler]; nothing is cached or stored. A query the
 * app should not send (no city, nothing asked) never leaves the phone.
 */
class RemoteExploreRepository(
    private val api: ExploreApi,
    private val handler: ApiCallHandler
) : ExploreRepository {

    override suspend fun search(query: ExploreQuery): ApiResult<ExploreResult> {
        if (!query.canSearch) return ApiResult.Failure(ApiError.UNKNOWN)
        return handler.execute { api.search(ExploreRequestDto.from(query)).toDomain() }
    }
}
