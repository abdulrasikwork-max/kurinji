package com.safesphere.app.feature.moderation.domain

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.ModerationReason

/**
 * Phase 7 moderation calls. The server checks the caller's role from its database on EVERY call (403 otherwise);
 * what the app shows or hides is only convenience.
 */
interface ModerationRepository {

    /** Whether to offer moderation at all (any signed-in account may ask). */
    suspend fun access(): ApiResult<ModerationAccess>

    /** Open cases, oldest first. */
    suspend fun openCases(): ApiResult<List<CaseSummary>>

    suspend fun caseDetail(caseId: String): ApiResult<CaseDetail>

    /**
     * One decision. [participantRef] and [days] only for RESTRICT; [wholeGroup] (Phase 9C) pauses every member of the
     * coordination group instead of one participant. Returns the case as it is afterwards.
     */
    suspend fun act(
        caseId: String,
        action: CaseAction,
        reason: ModerationReason,
        participantRef: String? = null,
        days: Int? = null,
        wholeGroup: Boolean = false
    ): ApiResult<CaseDetail>

    suspend fun openAppeals(): ApiResult<List<AppealItem>>

    suspend fun decideAppeal(appealId: String, decision: AppealStatus): ApiResult<AppealItem>
}
