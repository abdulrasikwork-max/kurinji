package com.safesphere.app.feature.membership.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant

/**
 * Phase N3 (A2): what verifiers and admins see and do (docs/PHASE_N3.md "Staff"). The roll number is never in an
 * answer: the person deciding types it from the ID card they hold. The server checks every role on every call; the
 * app only hides what the person cannot use.
 */
data class RequestDetail(
    val ref: String,
    val method: VerificationMethod,
    val name: String,
    val phoneHint: String?,
    val programmeLabel: String,
    val graduationYear: Int,
    val createdAt: Instant?,
    val photoUploadedAt: Instant?
) {
    override fun toString() = "RequestDetail(method=$method)"
}

/** The refusal reasons a person deciding may choose (ALREADY_MEMBER and BANNED are set by the server only). */
enum class RefusalReason { NAME_MISMATCH, ROLL_MISMATCH, NOT_THE_PERSON, NOT_MIET_ID, UNREADABLE, OTHER }

/** The ban reasons (shown to the person banned). */
enum class BanReason { HARASSMENT, HATE, THREATS, SEXUAL_CONTENT, IMPERSONATION, FALSE_ID, PRIVACY, REPEATED, OTHER }

enum class DecisionOutcome { VERIFIED, REFUSED }

/** The server's answer to a decision; [reason] is set for a refusal (also an automatic one: ALREADY_MEMBER, BANNED). */
data class Decision(val outcome: DecisionOutcome, val reason: String?)

data class VerifierRow(val name: String, val phoneHint: String?, val programmeLabel: String?, val graduationYear: Int?,
                       val since: Instant?)

data class BanRow(
    val id: Long,
    val reason: String,
    val note: String?,
    val onRollNumber: Boolean,
    val onPhone: Boolean,
    /** A current member the ban hit (by the roll number), else null. */
    val person: String?,
    val bannedBy: String?,
    val createdAt: Instant?,
    val liftedAt: Instant?
)

data class LogRow(val action: String, val method: String?, val reason: String?, val actor: String?, val subject: String?,
                  val detail: String?, val at: Instant?)

interface StaffRepository {
    /** A verifier or admin types the applicant's code; the request is claimed for them for 15 minutes. */
    suspend fun lookup(code: String): ApiResult<RequestDetail>
    suspend fun decideInPerson(ref: String, approve: Boolean, rollNumber: String?, name: String?,
                               reason: RefusalReason?): ApiResult<Decision>

    suspend fun photoQueue(): ApiResult<List<RequestDetail>>
    /** The decrypted ID photo, for the screen's memory only (never written to the phone). */
    suspend fun photo(ref: String): ApiResult<ByteArray>
    suspend fun decideByPhoto(ref: String, approve: Boolean, rollNumber: String?, name: String?,
                              reason: RefusalReason?): ApiResult<Decision>

    suspend fun verifiers(): ApiResult<List<VerifierRow>>
    suspend fun setVerifier(phone: String, verifier: Boolean): ApiResult<Unit>
    suspend fun correctName(phone: String, name: String): ApiResult<Unit>

    suspend fun bans(): ApiResult<List<BanRow>>
    suspend fun ban(phone: String?, rollNumber: String?, reason: BanReason, note: String?): ApiResult<Unit>
    suspend fun lift(id: Long): ApiResult<Unit>
    suspend fun endMembership(phone: String): ApiResult<Unit>
    /** N-25: move a member's account to the new number whose code the person shows, with the roll from the ID. */
    suspend fun relink(code: String, rollNumber: String): ApiResult<Unit>

    suspend fun log(page: Int): ApiResult<List<LogRow>>
}
