package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreRepository
import com.safesphere.app.feature.social.domain.ExploreResult
import com.safesphere.app.feature.social.domain.Understanding

/**
 * Phase 17G-1: records every query the screen would send (a structured query: it has no place for typed words).
 * Phase 17G-3: records every text handed to smart understanding (the ONLY call that carries the words) and answers what a
 * test sets; by default the server offers nothing, so the screen behaves exactly as in 17G-1.
 */
class FakeExploreRepository(
    var answer: ApiResult<ExploreResult> = ApiResult.Success(ExploreResult(null, 0, emptyList())),
    var available: Boolean = false,
    var understanding: ApiResult<Understanding> = ApiResult.Failure(ApiError.UNKNOWN)
) : ExploreRepository {

    val queries = mutableListOf<ExploreQuery>()
    val understoodTexts = mutableListOf<String>()

    override suspend fun search(query: ExploreQuery): ApiResult<ExploreResult> {
        queries += query
        return answer
    }

    override suspend fun understand(text: String): ApiResult<Understanding> {
        understoodTexts += text
        return understanding
    }

    override suspend fun understandingAvailable(): Boolean = available
}
