package com.safesphere.app.feature.moderation.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.ModerationReason
import com.safesphere.app.feature.moderation.data.remote.ActionRequestDto
import com.safesphere.app.feature.moderation.data.remote.AppealDecisionDto
import com.safesphere.app.feature.moderation.data.remote.ModerationApi
import com.safesphere.app.feature.moderation.domain.AppealItem
import com.safesphere.app.feature.moderation.domain.CaseAction
import com.safesphere.app.feature.moderation.domain.CaseDetail
import com.safesphere.app.feature.moderation.domain.CaseSummary
import com.safesphere.app.feature.moderation.domain.ModerationAccess
import com.safesphere.app.feature.moderation.domain.ModerationRepository

/** Moderation on the backend. All error handling is in [ApiCallHandler]; an unreadable body becomes UNKNOWN. */
class RemoteModerationRepository(
    private val api: ModerationApi,
    private val handler: ApiCallHandler
) : ModerationRepository {

    override suspend fun access(): ApiResult<ModerationAccess> = handler.execute { api.access().toDomain() }

    override suspend fun openCases(): ApiResult<List<CaseSummary>> =
        handler.execute { api.cases("OPEN").mapNotNull { it.toDomain() } }

    override suspend fun caseDetail(caseId: String): ApiResult<CaseDetail> =
        handler.execute { api.caseDetail(caseId).toDomain() ?: error("unreadable case") }

    override suspend fun act(
        caseId: String,
        action: CaseAction,
        reason: ModerationReason,
        participantRef: String?,
        days: Int?,
        wholeGroup: Boolean
    ): ApiResult<CaseDetail> = handler.execute {
        api.act(caseId, ActionRequestDto(action.name, reason.name, participantRef, days, wholeGroup.takeIf { it }))
            .toDomain() ?: error("unreadable case")
    }

    override suspend fun openAppeals(): ApiResult<List<AppealItem>> =
        handler.execute { api.appeals().mapNotNull { it.toDomain() } }

    override suspend fun decideAppeal(appealId: String, decision: AppealStatus): ApiResult<AppealItem> =
        handler.execute { api.decide(appealId, AppealDecisionDto(decision.name)).toDomain() ?: error("unreadable appeal") }
}
