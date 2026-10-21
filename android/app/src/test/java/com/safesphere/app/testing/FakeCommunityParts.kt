package com.safesphere.app.testing

import com.safesphere.app.core.location.GeoPoint
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.location.LocationProvider
import com.safesphere.app.core.location.LocationUpdate
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.community.domain.AreaSignal
import com.safesphere.app.feature.community.domain.AreaView
import com.safesphere.app.feature.intelligence.domain.AreaPicture
import com.safesphere.app.feature.community.domain.CommunityOutbox
import com.safesphere.app.feature.community.domain.CommunityRepository
import com.safesphere.app.feature.community.domain.CommunityStanding
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ContributionAccepted
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.MyContribution
import com.safesphere.app.feature.community.domain.PendingReport
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.community.domain.SignalFreshness
import com.safesphere.app.feature.community.domain.SignalPrecision
import com.safesphere.app.feature.community.domain.SignalSupport
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Records every call; answers come from the public fields. */
class FakeCommunityRepository : CommunityRepository {
    var areaResult: ApiResult<List<AreaSignal>> = ApiResult.Success(emptyList())
    var reportResult: ApiResult<ContributionAccepted> = ApiResult.Success(ContributionAccepted(30))
    var contributeResult: ApiResult<ContributionAccepted> = ApiResult.Success(ContributionAccepted(30))
    var mineResult: ApiResult<List<MyContribution>> = ApiResult.Success(emptyList())
    var withdrawResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val areaCalls = mutableListOf<String>()
    val reports = mutableListOf<Pair<SignalCategory, String>>()
    val contributions = mutableListOf<Triple<String, ContributionKind, String>>()
    val withdrawn = mutableListOf<String>()

    /** Phase 8: the picture sent with a successful [areaResult]. */
    var picture: AreaPicture? = null

    override suspend fun area(areaCell: String): ApiResult<AreaView> {
        areaCalls += areaCell
        return when (val result = areaResult) {
            is ApiResult.Success -> ApiResult.Success(AreaView(result.data, picture))
            is ApiResult.Failure -> result
        }
    }
    override suspend fun report(category: SignalCategory, blockCell: String) = reportResult.also { reports += category to blockCell }
    override suspend fun contribute(signalId: String, kind: ContributionKind, nearCell: String) =
        contributeResult.also { contributions += Triple(signalId, kind, nearCell) }
    override suspend fun mine() = mineResult
    override suspend fun withdraw(signalId: String) = withdrawResult.also { withdrawn += signalId }

    // Phase 7
    var flagResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var standingResult: ApiResult<CommunityStanding> = ApiResult.Success(CommunityStanding.ACTIVE)
    var appealResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val flags = mutableListOf<Triple<String, FlagReason, String>>()
    var appeals = 0

    override suspend fun flag(signalId: String, reason: FlagReason, nearCell: String) =
        flagResult.also { flags += Triple(signalId, reason, nearCell) }
    override suspend fun standing() = standingResult
    override suspend fun appeal() = appealResult.also { appeals++ }
}

class FakeCommunityOutbox(var stored: List<PendingReport> = emptyList()) : CommunityOutbox {
    var cleared = false
    override suspend fun load() = stored
    override suspend fun save(reports: List<PendingReport>) { stored = reports }
    override suspend fun clear() { stored = emptyList(); cleared = true }
}

/** Every collection gets [next] once (null = no answer ever, to test the timeout). */
class ScriptedLocationProvider(
    var access: LocationAccess = LocationAccess.PRECISE,
    var next: LocationUpdate? = LocationUpdate.Fix(FakeLocationProvider.fix())
) : LocationProvider {
    var subscriptions = 0
    override fun access() = access
    override fun isLocationEnabled() = true
    override fun updates(intervalMillis: Long): Flow<LocationUpdate> = flow {
        subscriptions++
        val update = next ?: awaitCancellation()
        emit(update)
        awaitCancellation()
    }
}

fun areaSignal(
    id: String = "s1",
    category: SignalCategory = SignalCategory.POOR_LIGHTING,
    centre: GeoPoint = GeoPoint(12.975, 77.5946),
    precision: SignalPrecision = SignalPrecision.AREA,
    yours: ContributionKind? = null
) = AreaSignal(id, category, precision, "tdr1v9", centre, SignalSupport.SINGLE, SignalFreshness.TODAY, false, yours)
