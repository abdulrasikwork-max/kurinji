package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moderation.domain.ModerationAccess
import com.safesphere.app.feature.moderation.domain.ModerationRepository

/** Answers come from the public field. */
class FakeModerationRepository : ModerationRepository {
    var accessResult: ApiResult<ModerationAccess> = ApiResult.Success(ModerationAccess(true, false))
    var accessCalls = 0

    override suspend fun access(): ApiResult<ModerationAccess> {
        accessCalls++
        return accessResult
    }
}
