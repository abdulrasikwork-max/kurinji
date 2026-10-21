package com.safesphere.app.feature.community.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.data.remote.CommunityApi
import com.safesphere.app.feature.community.data.remote.ContributionRequestDto
import com.safesphere.app.feature.community.data.remote.FlagRequestDto
import com.safesphere.app.feature.community.data.remote.ReportRequestDto
import com.safesphere.app.feature.community.domain.AreaView
import com.safesphere.app.feature.community.domain.CommunityRepository
import com.safesphere.app.feature.community.domain.CommunityStanding
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ContributionAccepted
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.MyContribution
import com.safesphere.app.feature.community.domain.SignalCategory

/** Place signals on the backend. All error handling is in [ApiCallHandler]. */
class RemoteCommunityRepository(
    private val api: CommunityApi,
    private val handler: ApiCallHandler
) : CommunityRepository {

    override suspend fun area(areaCell: String): ApiResult<AreaView> =
        handler.execute { api.area(areaCell).toView() }

    override suspend fun report(category: SignalCategory, blockCell: String): ApiResult<ContributionAccepted> =
        handler.execute { api.report(ReportRequestDto(category.name, blockCell)).toDomain() }

    override suspend fun contribute(signalId: String, kind: ContributionKind, nearCell: String): ApiResult<ContributionAccepted> =
        handler.execute { api.contribute(signalId, ContributionRequestDto(kind.name, nearCell)).toDomain() }

    override suspend fun mine(): ApiResult<List<MyContribution>> =
        handler.execute { api.mine().mapNotNull { it.toDomain() } }

    override suspend fun withdraw(signalId: String): ApiResult<Unit> =
        handler.execute { api.withdraw(signalId) }

    override suspend fun flag(signalId: String, reason: FlagReason, nearCell: String): ApiResult<Unit> =
        handler.execute { api.flag(signalId, FlagRequestDto(reason.name, nearCell)); Unit }

    override suspend fun standing(): ApiResult<CommunityStanding> =
        handler.execute { api.standing().toDomain() }

    override suspend fun appeal(): ApiResult<Unit> =
        handler.execute { api.appeal(emptyMap()); Unit }
}
