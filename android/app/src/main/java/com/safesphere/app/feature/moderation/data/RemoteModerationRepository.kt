package com.safesphere.app.feature.moderation.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moderation.data.remote.ModerationApi
import com.safesphere.app.feature.moderation.domain.ModerationAccess
import com.safesphere.app.feature.moderation.domain.ModerationRepository

/** All error handling is in [ApiCallHandler]. */
class RemoteModerationRepository(
    private val api: ModerationApi,
    private val handler: ApiCallHandler
) : ModerationRepository {

    override suspend fun access(): ApiResult<ModerationAccess> = handler.execute { api.access().toDomain() }
}
