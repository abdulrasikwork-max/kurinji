package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.membership.data.IdPhotoShot
import com.safesphere.app.feature.membership.data.IdPhotoSource
import com.safesphere.app.feature.membership.domain.CommunityStatus
import com.safesphere.app.feature.membership.domain.MembershipRepository
import com.safesphere.app.feature.membership.domain.MembershipState
import com.safesphere.app.feature.membership.domain.OpenRequest
import com.safesphere.app.feature.membership.domain.Programme
import com.safesphere.app.feature.membership.domain.RelinkCode
import com.safesphere.app.feature.membership.domain.VerificationMethod

/** Phase N3: a membership server in memory. Each answer can be replaced; every call is recorded. */
class FakeMembershipRepository : MembershipRepository {

    val programmeList = listOf(
        Programme("BTECH_IT", "B.Tech Information Technology", false, 2026, 2030),
        Programme("MBA", "MBA", true, 2026, 2028)
    )

    var statusResult: ApiResult<CommunityStatus> = ApiResult.Success(status(MembershipState.NONE))
    var startResult: ApiResult<CommunityStatus> = ApiResult.Success(pending("ABCD-2345"))
    var newCodeResult: ApiResult<CommunityStatus> = ApiResult.Success(pending("WXYZ-6789"))
    var uploadResult: ApiResult<CommunityStatus> = ApiResult.Success(photoPending(uploaded = true))
    var withdrawResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var relinkResult: ApiResult<RelinkCode> = ApiResult.Success(RelinkCode("PQRS-2345", 3600))

    val starts = mutableListOf<Triple<VerificationMethod, String, Pair<String, Int>>>()
    val uploads = mutableListOf<ByteArray>()
    val relinks = mutableListOf<Pair<String, String>>()
    var statusCalls = 0
    var withdrawCalls = 0
    var newCodeCalls = 0

    override suspend fun status(): ApiResult<CommunityStatus> {
        statusCalls++
        return statusResult
    }

    override suspend fun programmes(): ApiResult<List<Programme>> = ApiResult.Success(programmeList)

    override suspend fun start(method: VerificationMethod, rollNumber: String, programme: String,
                               graduationYear: Int): ApiResult<CommunityStatus> {
        starts += Triple(method, rollNumber, programme to graduationYear)
        return startResult
    }

    override suspend fun newCode(): ApiResult<CommunityStatus> {
        newCodeCalls++
        return newCodeResult
    }

    override suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<CommunityStatus> {
        uploads += jpeg
        return uploadResult
    }

    override suspend fun withdraw(): ApiResult<Unit> {
        withdrawCalls++
        return withdrawResult
    }

    override suspend fun relink(ticket: String, rollNumber: String): ApiResult<RelinkCode> {
        relinks += ticket to rollNumber
        return relinkResult
    }

    companion object {
        fun status(state: MembershipState, request: OpenRequest? = null, nameLocked: Boolean = false) =
            CommunityStatus(state, "MIET Engineering College", null, request, null, null, nameLocked,
                verifier = false, admin = false)

        fun pending(code: String?, alive: Boolean = code != null) = status(MembershipState.PENDING,
            OpenRequest(VerificationMethod.IN_PERSON, "B.Tech Information Technology", 2027, code, alive, 600,
                photoUploaded = false, expiresAt = null))

        fun photoPending(uploaded: Boolean) = status(MembershipState.PENDING,
            OpenRequest(VerificationMethod.ID_PHOTO, "MBA", 2027, null, false, 0, uploaded, null))
    }
}

/** A photo source that returns [prepared] and records what was prepared and deleted. */
class FakeIdPhotoSource(var prepared: ByteArray? = byteArrayOf(1, 2, 3)) : IdPhotoSource {
    val preparedShots = mutableListOf<IdPhotoShot>()
    val discarded = mutableListOf<IdPhotoShot>()

    override suspend fun prepare(shot: IdPhotoShot): ByteArray? {
        preparedShots += shot
        return prepared
    }

    override fun discard(shot: IdPhotoShot) {
        discarded += shot
    }
}

class FakeShot : IdPhotoShot
