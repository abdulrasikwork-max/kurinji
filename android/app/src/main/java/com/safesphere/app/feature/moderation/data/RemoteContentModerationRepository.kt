package com.safesphere.app.feature.moderation.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moderation.data.remote.AppealDecisionDto
import com.safesphere.app.feature.moderation.data.remote.ContentActionDto
import com.safesphere.app.feature.moderation.data.remote.ContentModerationApi
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentAppeal
import com.safesphere.app.feature.moderation.domain.ContentCaseDetail
import com.safesphere.app.feature.moderation.domain.ContentCaseSummary
import com.safesphere.app.feature.moderation.domain.ContentDecisionReason
import com.safesphere.app.feature.moderation.domain.ContentModerationRepository

/** Phase 13D: the content queue on the backend. All error handling is in [ApiCallHandler]; nothing is cached. */
class RemoteContentModerationRepository(
    private val api: ContentModerationApi,
    private val handler: ApiCallHandler,
    private val baseUrl: String
) : ContentModerationRepository {

    override suspend fun openCases(): ApiResult<List<ContentCaseSummary>> =
        handler.execute { api.cases("OPEN").mapNotNull { it.toDomain() } }

    override suspend fun caseDetail(caseId: String): ApiResult<ContentCaseDetail> =
        handler.execute { api.caseDetail(caseId).toDomain() ?: error("unreadable case") }

    override suspend fun act(caseId: String, action: ContentAction, reason: ContentDecisionReason): ApiResult<ContentCaseDetail> =
        handler.execute { api.act(caseId, ContentActionDto(action.name, reason.name)).toDomain() ?: error("unreadable case") }

    override suspend fun openAppeals(): ApiResult<List<ContentAppeal>> =
        handler.execute { api.appeals().mapNotNull { it.toDomain() } }

    override suspend fun decideAppeal(appealId: String, overturn: Boolean): ApiResult<ContentAppeal> = handler.execute {
        api.decide(appealId, AppealDecisionDto(if (overturn) "OVERTURNED" else "UPHELD")).toDomain()
            ?: error("unreadable appeal")
    }

    override fun photoUrl(caseId: String, mediaId: String): String =
        baseUrl.trimEnd('/') + "/api/moderation/content-cases/" + caseId + "/media/" + mediaId
}
