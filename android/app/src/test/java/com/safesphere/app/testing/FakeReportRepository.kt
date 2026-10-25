package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.MyReport
import com.safesphere.app.feature.social.domain.ReportReason
import com.safesphere.app.feature.social.domain.ReportRepository
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.domain.SocialStanding

/** Test double (Phase 13D): records reports and appeals; answers what the test sets. */
class FakeReportRepository : ReportRepository {

    data class Sent(val target: ReportTarget, val targetId: String, val reason: ReportReason, val note: String?)

    val sent = mutableListOf<Sent>()
    val appeals = mutableListOf<String>()
    var reportResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var appealResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var mine: ApiResult<List<MyReport>> = ApiResult.Success(emptyList())
    var standing: ApiResult<SocialStanding> = ApiResult.Success(SocialStanding(null, emptyList()))

    override suspend fun report(target: ReportTarget, targetId: String, reason: ReportReason, note: String?): ApiResult<Unit> {
        sent += Sent(target, targetId, reason, note)
        return reportResult
    }

    override suspend fun myReports(): ApiResult<List<MyReport>> = mine

    override suspend fun standing(): ApiResult<SocialStanding> = standing

    override suspend fun appeal(decisionId: String): ApiResult<Unit> {
        appeals += decisionId
        return appealResult
    }

    /** Phase 13E. */
    val checkReviews = mutableListOf<Pair<ReportTarget, String>>()

    override suspend fun askCheckReview(target: ReportTarget, targetId: String): ApiResult<Unit> {
        checkReviews += target to targetId
        return appealResult
    }
}
