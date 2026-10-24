package com.safesphere.app.feature.social.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.ReportApi
import com.safesphere.app.feature.social.data.remote.ReportRequestDto
import com.safesphere.app.feature.social.domain.MyReport
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportRepository
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.domain.SocialStanding

/** Phase 13D: reports and standing on the backend. All error handling is in [ApiCallHandler]; nothing is cached. */
class RemoteReportRepository(private val api: ReportApi, private val handler: ApiCallHandler) : ReportRepository {

    override suspend fun report(target: ReportTarget, targetId: String, reason: ReportReason, note: String?): ApiResult<Unit> =
        handler.execute {
            api.report(ReportRequestDto(target.name, targetId, reason.name,
                if (reason == ReportReason.OTHER) note?.trim()?.ifEmpty { null } else null))
            Unit
        }

    override suspend fun myReports(): ApiResult<List<MyReport>> = handler.execute { api.mine().mapNotNull { it.toDomain() } }

    override suspend fun standing(): ApiResult<SocialStanding> = handler.execute { api.standing().toDomain() }

    override suspend fun appeal(decisionId: String): ApiResult<Unit> = handler.execute {
        api.appeal(decisionId)
        Unit
    }
}
