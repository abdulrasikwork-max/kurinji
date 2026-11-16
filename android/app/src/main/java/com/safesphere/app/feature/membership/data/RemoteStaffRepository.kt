package com.safesphere.app.feature.membership.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.auth.domain.PhonePolicy
import com.safesphere.app.feature.membership.data.remote.BanDto
import com.safesphere.app.feature.membership.data.remote.BanRequestDto
import com.safesphere.app.feature.membership.data.remote.DecisionDto
import com.safesphere.app.feature.membership.data.remote.DecisionResultDto
import com.safesphere.app.feature.membership.data.remote.LogDto
import com.safesphere.app.feature.membership.data.remote.LookupDto
import com.safesphere.app.feature.membership.data.remote.NameChangeDto
import com.safesphere.app.feature.membership.data.remote.PhoneDto
import com.safesphere.app.feature.membership.data.remote.RelinkDecisionDto
import com.safesphere.app.feature.membership.data.remote.RequestDetailDto
import com.safesphere.app.feature.membership.data.remote.StaffApi
import com.safesphere.app.feature.membership.data.remote.VerifierChangeDto
import com.safesphere.app.feature.membership.data.remote.VerifierDto
import com.safesphere.app.feature.membership.domain.BanReason
import com.safesphere.app.feature.membership.domain.BanRow
import com.safesphere.app.feature.membership.domain.Decision
import com.safesphere.app.feature.membership.domain.DecisionOutcome
import com.safesphere.app.feature.membership.domain.LogRow
import com.safesphere.app.feature.membership.domain.RefusalReason
import com.safesphere.app.feature.membership.domain.RequestDetail
import com.safesphere.app.feature.membership.domain.RollNumberPolicy
import com.safesphere.app.feature.membership.domain.StaffRepository
import com.safesphere.app.feature.membership.domain.VerificationMethod
import com.safesphere.app.feature.membership.domain.VerifierRow
import java.time.Instant

/**
 * Phase N3 (A2): the staff side over [ApiCallHandler]. Nothing is stored on the phone; the ID photo comes back as
 * bytes for the screen's memory. Numbers are normalised on the phone first (a bad one is never sent); answers fail
 * closed (an unreadable row is dropped, an unknown outcome is a failure, never "verified").
 */
class RemoteStaffRepository(
    private val api: StaffApi,
    private val handler: ApiCallHandler
) : StaffRepository {

    override suspend fun lookup(code: String): ApiResult<RequestDetail> {
        val cleaned = cleanCode(code) ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.lookup(LookupDto(cleaned)) }.andThen { it.toDomain()?.let(::ok) ?: unknown() }
    }

    override suspend fun decideInPerson(ref: String, approve: Boolean, rollNumber: String?, name: String?,
                                        reason: RefusalReason?): ApiResult<Decision> {
        val body = decision(approve, rollNumber, name, reason) ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.decideInPerson(ref, body) }.andThen(::outcome)
    }

    override suspend fun photoQueue(): ApiResult<List<RequestDetail>> =
        handler.execute { api.photoQueue() }.andThen { list -> ok(list.mapNotNull { it.toDomain() }) }

    override suspend fun photo(ref: String): ApiResult<ByteArray> =
        handler.execute { api.photo(ref).use { it.bytes() } }

    override suspend fun decideByPhoto(ref: String, approve: Boolean, rollNumber: String?, name: String?,
                                       reason: RefusalReason?): ApiResult<Decision> {
        val body = decision(approve, rollNumber, name, reason) ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.decideByPhoto(ref, body) }.andThen(::outcome)
    }

    override suspend fun verifiers(): ApiResult<List<VerifierRow>> =
        handler.execute { api.verifiers() }.andThen { list -> ok(list.mapNotNull(VerifierDto::toDomain)) }

    override suspend fun setVerifier(phone: String, verifier: Boolean): ApiResult<Unit> {
        val number = PhonePolicy.normalize(phone) ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.setVerifier(VerifierChangeDto(number, verifier)) }
    }

    override suspend fun correctName(phone: String, name: String): ApiResult<Unit> {
        val number = PhonePolicy.normalize(phone) ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.correctName(NameChangeDto(number, name)) }
    }

    override suspend fun bans(): ApiResult<List<BanRow>> =
        handler.execute { api.bans() }.andThen { list -> ok(list.mapNotNull(BanDto::toDomain)) }

    override suspend fun ban(phone: String?, rollNumber: String?, reason: BanReason, note: String?): ApiResult<Unit> {
        val number = phone?.takeIf { it.isNotBlank() }?.let { PhonePolicy.normalize(it) ?: return ApiResult.Failure(ApiError.VALIDATION) }
        val roll = rollNumber?.takeIf { it.isNotBlank() }?.let {
            RollNumberPolicy.normalise(it)?.takeIf(RollNumberPolicy::accepts) ?: return ApiResult.Failure(ApiError.VALIDATION)
        }
        if (number == null && roll == null) return ApiResult.Failure(ApiError.VALIDATION)
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }?.take(NOTE_MAX)
        return handler.execute { api.ban(BanRequestDto(number, roll, reason.name, cleanNote)) }.andThen { ok(Unit) }
    }

    override suspend fun lift(id: Long): ApiResult<Unit> = handler.execute { api.lift(id) }.andThen { ok(Unit) }

    override suspend fun endMembership(phone: String): ApiResult<Unit> {
        val number = PhonePolicy.normalize(phone) ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.endMembership(PhoneDto(number)) }
    }

    override suspend fun relink(code: String, rollNumber: String): ApiResult<Unit> {
        val cleaned = cleanCode(code) ?: return ApiResult.Failure(ApiError.VALIDATION)
        val roll = RollNumberPolicy.normalise(rollNumber)?.takeIf(RollNumberPolicy::accepts)
            ?: return ApiResult.Failure(ApiError.VALIDATION)
        return handler.execute { api.relink(RelinkDecisionDto(cleaned, roll)) }.andThen { ok(Unit) }
    }

    override suspend fun log(page: Int): ApiResult<List<LogRow>> =
        handler.execute { api.log(page.coerceAtLeast(0)) }.andThen { list -> ok(list.mapNotNull(LogDto::toDomain)) }

    private fun decision(approve: Boolean, rollNumber: String?, name: String?, reason: RefusalReason?): DecisionDto? {
        if (!approve) return reason?.let { DecisionDto(false, null, null, it.name) }
        val roll = RollNumberPolicy.normalise(rollNumber)?.takeIf(RollNumberPolicy::accepts) ?: return null
        return DecisionDto(true, roll, name?.trim()?.takeIf { it.isNotEmpty() }, null)
    }

    private fun outcome(dto: DecisionResultDto): ApiResult<Decision> {
        val kind = DecisionOutcome.entries.firstOrNull { it.name == dto.outcome } ?: return unknown()
        return ok(Decision(kind, dto.reason?.takeIf { it.isNotBlank() }))
    }

    companion object {
        const val NOTE_MAX = 200

        /** "abcd 2345", "ABCD-2345" → "ABCD2345"; null when it cannot be one of the server's 8-character codes. */
        fun cleanCode(raw: String?): String? {
            val out = raw.orEmpty().uppercase().filter { it.isLetterOrDigit() }
            return out.takeIf { Regex("^[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}$").matches(it) }
        }
    }
}

private fun <T> ok(value: T): ApiResult<T> = ApiResult.Success(value)
private fun <T> unknown(): ApiResult<T> = ApiResult.Failure(ApiError.UNKNOWN)

private inline fun <T, R> ApiResult<T>.andThen(next: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
    is ApiResult.Success -> next(data)
    is ApiResult.Failure -> this
}

private fun instant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun RequestDetailDto.toDomain(): RequestDetail? {
    val ref = ref?.takeIf { it.isNotBlank() } ?: return null
    val method = VerificationMethod.entries.firstOrNull { it.name == method } ?: return null
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    val label = programmeLabel?.takeIf { it.isNotBlank() } ?: return null
    val year = graduationYear ?: return null
    return RequestDetail(ref, method, name, phoneHint?.takeIf { it.isNotBlank() }, label, year, instant(createdAt),
        instant(photoUploadedAt))
}

internal fun VerifierDto.toDomain(): VerifierRow? {
    val name = name?.takeIf { it.isNotBlank() } ?: return null
    return VerifierRow(name, phoneHint, programme, graduationYear, instant(since))
}

internal fun BanDto.toDomain(): BanRow? {
    val id = id ?: return null
    val reason = reason?.takeIf { it.isNotBlank() } ?: return null
    return BanRow(id, reason, note?.takeIf { it.isNotBlank() }, onRollNumber == true, onPhone == true,
        person?.takeIf { it.isNotBlank() }, bannedBy?.takeIf { it.isNotBlank() }, instant(createdAt), instant(liftedAt))
}

internal fun LogDto.toDomain(): LogRow? {
    val action = action?.takeIf { it.isNotBlank() } ?: return null
    return LogRow(action, method, reason, actor, subject, detail, instant(at))
}
