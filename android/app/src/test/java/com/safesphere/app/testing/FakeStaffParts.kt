package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.membership.domain.BanReason
import com.safesphere.app.feature.membership.domain.BanRow
import com.safesphere.app.feature.membership.domain.Decision
import com.safesphere.app.feature.membership.domain.DecisionOutcome
import com.safesphere.app.feature.membership.domain.LogRow
import com.safesphere.app.feature.membership.domain.RefusalReason
import com.safesphere.app.feature.membership.domain.RequestDetail
import com.safesphere.app.feature.membership.domain.StaffRepository
import com.safesphere.app.feature.membership.domain.VerificationMethod
import com.safesphere.app.feature.membership.domain.VerifierRow

/** Phase N3 (A2): the staff server in memory; every call is recorded, every answer can be replaced. */
class FakeStaffRepository : StaffRepository {

    val inPerson = RequestDetail("ref-1", VerificationMethod.IN_PERSON, "Nila Check", "••••••1111",
        "B.Tech Information Technology", 2027, null, null)
    val byPhoto = RequestDetail("ref-2", VerificationMethod.ID_PHOTO, "Meera Test", "••••••2222", "MBA", 2027, null, null)

    var lookupResult: ApiResult<RequestDetail> = ApiResult.Success(inPerson)
    var decisionResult: ApiResult<Decision> = ApiResult.Success(Decision(DecisionOutcome.VERIFIED, null))
    var queueResult: ApiResult<List<RequestDetail>> = ApiResult.Success(listOf(byPhoto))
    var photoResult: ApiResult<ByteArray> = ApiResult.Success(byteArrayOf(7, 7, 7))
    var unitResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var bansResult: ApiResult<List<BanRow>> = ApiResult.Success(emptyList())
    var logPages: Map<Int, List<LogRow>> = emptyMap()

    val lookups = mutableListOf<String>()
    val decisions = mutableListOf<String>()
    val actions = mutableListOf<String>()
    var bansCalls = 0

    override suspend fun lookup(code: String): ApiResult<RequestDetail> {
        lookups += code
        return lookupResult
    }

    override suspend fun decideInPerson(ref: String, approve: Boolean, rollNumber: String?, name: String?,
                                        reason: RefusalReason?): ApiResult<Decision> {
        decisions += "person:$ref:$approve:$rollNumber:$name:$reason"
        return decisionResult
    }

    override suspend fun photoQueue(): ApiResult<List<RequestDetail>> = queueResult
    override suspend fun photo(ref: String): ApiResult<ByteArray> = photoResult

    override suspend fun decideByPhoto(ref: String, approve: Boolean, rollNumber: String?, name: String?,
                                       reason: RefusalReason?): ApiResult<Decision> {
        decisions += "photo:$ref:$approve:$rollNumber:$name:$reason"
        return decisionResult
    }

    override suspend fun verifiers(): ApiResult<List<VerifierRow>> = ApiResult.Success(emptyList())

    override suspend fun setVerifier(phone: String, verifier: Boolean): ApiResult<Unit> {
        actions += "verifier:$phone:$verifier"
        return unitResult
    }

    override suspend fun correctName(phone: String, name: String): ApiResult<Unit> {
        actions += "name:$phone:$name"
        return unitResult
    }

    override suspend fun bans(): ApiResult<List<BanRow>> {
        bansCalls++
        return bansResult
    }

    override suspend fun ban(phone: String?, rollNumber: String?, reason: BanReason, note: String?): ApiResult<Unit> {
        actions += "ban:$phone:$rollNumber:$reason:$note"
        return unitResult
    }

    override suspend fun lift(id: Long): ApiResult<Unit> {
        actions += "lift:$id"
        return unitResult
    }

    override suspend fun endMembership(phone: String): ApiResult<Unit> {
        actions += "end:$phone"
        return unitResult
    }

    override suspend fun relink(code: String, rollNumber: String): ApiResult<Unit> {
        actions += "relink:$code:$rollNumber"
        return unitResult
    }

    override suspend fun log(page: Int): ApiResult<List<LogRow>> = ApiResult.Success(logPages[page].orEmpty())
}
