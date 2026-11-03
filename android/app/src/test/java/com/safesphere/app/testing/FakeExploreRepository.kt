package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.ExploreQuery
import com.safesphere.app.feature.social.domain.ExploreRepository
import com.safesphere.app.feature.social.domain.ExploreResult

/** Phase 17G-1: records every query the screen would send (a structured query: it has no place for typed words). */
class FakeExploreRepository(
    var answer: ApiResult<ExploreResult> = ApiResult.Success(ExploreResult(null, 0, emptyList()))
) : ExploreRepository {

    val queries = mutableListOf<ExploreQuery>()

    override suspend fun search(query: ExploreQuery): ApiResult<ExploreResult> {
        queries += query
        return answer
    }
}
