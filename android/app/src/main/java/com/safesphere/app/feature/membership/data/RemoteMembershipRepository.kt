package com.safesphere.app.feature.membership.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.membership.data.remote.MeDto
import com.safesphere.app.feature.membership.data.remote.MembershipApi
import com.safesphere.app.feature.membership.data.remote.ProgrammeDto
import com.safesphere.app.feature.membership.data.remote.RelinkStartDto
import com.safesphere.app.feature.membership.data.remote.StartRequestDto
import com.safesphere.app.feature.membership.domain.CommunityStatus
import com.safesphere.app.feature.membership.domain.MembershipInfo
import com.safesphere.app.feature.membership.domain.MembershipRepository
import com.safesphere.app.feature.membership.domain.MembershipState
import com.safesphere.app.feature.membership.domain.NameCorrection
import com.safesphere.app.feature.membership.domain.OpenRequest
import com.safesphere.app.feature.membership.domain.Outcome
import com.safesphere.app.feature.membership.domain.OutcomeKind
import com.safesphere.app.feature.membership.domain.Programme
import com.safesphere.app.feature.membership.domain.RelinkCode
import com.safesphere.app.feature.membership.domain.RollNumberPolicy
import com.safesphere.app.feature.membership.domain.ShortCode
import com.safesphere.app.feature.membership.domain.VerificationMethod
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Phase N3: the applicant's side over [ApiCallHandler]. Nothing is stored on the phone. The answers fail closed: an
 * unknown state reads as NONE (no access is ever assumed), an unknown method drops the request, a malformed code is
 * not shown, staff flags default to false.
 */
class RemoteMembershipRepository(
    private val api: MembershipApi,
    private val handler: ApiCallHandler
) : MembershipRepository {

    override suspend fun status(): ApiResult<CommunityStatus> = handler.execute { api.me() }.map(MeDto::toDomain)

    override suspend fun programmes(): ApiResult<List<Programme>> =
        handler.execute { api.programmes() }.map { list -> list.mapNotNull(ProgrammeDto::toDomain) }

    override suspend fun start(method: VerificationMethod, rollNumber: String, programme: String,
                               graduationYear: Int): ApiResult<CommunityStatus> {
        // The roll number leaves the phone normalised (as the server would make it); a bad one never leaves.
        val roll = RollNumberPolicy.normalise(rollNumber)?.takeIf { RollNumberPolicy.accepts(it) }
            ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.start(StartRequestDto(method.name, roll, programme, graduationYear)) }
            .map(MeDto::toDomain)
    }

    override suspend fun newCode(): ApiResult<CommunityStatus> = handler.execute { api.newCode() }.map(MeDto::toDomain)

    override suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<CommunityStatus> =
        handler.execute { api.uploadPhoto(jpeg.toRequestBody(JPEG)) }.map(MeDto::toDomain)

    override suspend fun withdraw(): ApiResult<Unit> = handler.execute { api.withdraw() }

    override suspend fun relink(ticket: String, rollNumber: String): ApiResult<RelinkCode> {
        val roll = RollNumberPolicy.normalise(rollNumber)?.takeIf { RollNumberPolicy.accepts(it) }
            ?: return ApiResult.Failure(ApiError.VALIDATION)
        return when (val answer = handler.execute { api.relink(RelinkStartDto(ticket, roll)) }) {
            is ApiResult.Failure -> answer
            is ApiResult.Success -> {
                val code = ShortCode.clean(answer.data.code)
                if (code == null) ApiResult.Failure(ApiError.UNKNOWN)
                else ApiResult.Success(RelinkCode(code, (answer.data.expiresInSeconds ?: 0).coerceAtLeast(0)))
            }
        }
    }

    private companion object {
        val JPEG = "image/jpeg".toMediaType()
    }
}

private fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> this
}

internal fun ProgrammeDto.toDomain(): Programme? {
    val code = code?.takeIf { it.isNotBlank() } ?: return null
    val label = label?.takeIf { it.isNotBlank() } ?: return null
    val first = firstYear ?: return null
    val last = lastYear ?: return null
    if (last < first) return null
    return Programme(code, label, level == "PG", first, last)
}

internal fun MeDto.toDomain(): CommunityStatus {
    val state = MembershipState.entries.firstOrNull { it.name == state } ?: MembershipState.NONE
    val membership = membership?.let { m ->
        val label = m.programmeLabel?.takeIf { it.isNotBlank() }
        val year = m.graduationYear
        if (label == null || year == null) null
        else MembershipInfo(label, year, methodOf(m.method), instant(m.verifiedAt), instant(m.endsAt))
    }
    val request = request?.let { r ->
        val method = methodOf(r.method)
        val label = r.programmeLabel?.takeIf { it.isNotBlank() }
        val year = r.graduationYear
        if (method == null || label == null || year == null) null
        else OpenRequest(method, label, year,
            code = if (method == VerificationMethod.IN_PERSON) ShortCode.clean(r.code) else null,
            codeAlive = method == VerificationMethod.IN_PERSON && r.codeAlive == true,
            codeExpiresInSeconds = (r.codeExpiresInSeconds ?: 0).coerceAtLeast(0),
            photoUploaded = method == VerificationMethod.ID_PHOTO && r.photoUploaded == true,
            expiresAt = instant(r.expiresAt))
    }
    val outcome = outcome?.let { o ->
        OutcomeKind.entries.firstOrNull { it.name == o.kind }?.let { Outcome(it, o.reason?.takeIf(String::isNotBlank), instant(o.at)) }
    }
    val correction = nameCorrection?.from?.takeIf { it.isNotBlank() }?.let { NameCorrection(it, instant(nameCorrection.at)) }
    return CommunityStatus(
        state = state,
        communityName = community?.name?.takeIf { it.isNotBlank() },
        membership = membership,
        // A PENDING state without a readable request is shown as PENDING with nothing to act on but "withdraw".
        request = request,
        outcome = outcome,
        nameCorrection = correction,
        nameLocked = nameLocked == true,
        verifier = staff?.verifier == true,
        admin = staff?.admin == true
    )
}

private fun methodOf(name: String?): VerificationMethod? = VerificationMethod.entries.firstOrNull { it.name == name }

private fun instant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }
