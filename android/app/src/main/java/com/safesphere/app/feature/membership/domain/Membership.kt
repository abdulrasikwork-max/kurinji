package com.safesphere.app.feature.membership.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant

/**
 * Phase N3: the person's place in the MIET community, as `GET /api/community/me` tells it (docs/PHASE_N3.md). Nothing
 * here is stored on the phone; the screens ask again each time they open.
 */
enum class MembershipState {
    /** Signed up, not verified: safety, account, settings and the own profile draft work; the community does not. */
    NONE,
    /** One open request (in person with a short code, or an ID photo waiting for an admin). */
    PENDING,
    /** A verified member: the community opens and the real name is locked. */
    MEMBER,
    /** Graduated, or ended by an admin: as NONE, and may ask again. */
    ENDED,
    /** An admin's ban on the roll number or the phone number: no community; sign-in and safety unchanged. */
    BANNED
}

/** The two alternative ways (owner's item 3): in person is the default and preferred; an ID photo is the fallback. */
enum class VerificationMethod { IN_PERSON, ID_PHOTO }

/** One programme of the owner's closed list (no seat counts). */
data class Programme(
    val code: String,
    val label: String,
    val postgraduate: Boolean,
    val firstYear: Int,
    val lastYear: Int
) {
    /** The graduation years that can be chosen, latest last. */
    val years: List<Int> get() = if (lastYear >= firstYear) (firstYear..lastYear).toList() else emptyList()
}

data class MembershipInfo(
    val programmeLabel: String,
    val graduationYear: Int,
    val method: VerificationMethod?,
    val verifiedAt: Instant?,
    /** The end of 31 July of the graduation year, India time; access stops then. */
    val endsAt: Instant?
)

/**
 * The open request. [code] is set ONLY in the answer that made it (start or "show a new code"): the server keeps just
 * its HMAC, so the code lives in this screen's memory and nowhere else.
 */
data class OpenRequest(
    val method: VerificationMethod,
    val programmeLabel: String,
    val graduationYear: Int,
    val code: String?,
    val codeAlive: Boolean,
    val codeExpiresInSeconds: Long,
    val photoUploaded: Boolean,
    val expiresAt: Instant?
) {
    override fun toString() = "OpenRequest(method=$method, code=${if (code == null) "none" else "<hidden>"}, " +
        "codeAlive=$codeAlive, photoUploaded=$photoUploaded)"
}

enum class OutcomeKind { REFUSED, ENDED, BANNED }

/** The latest refusal, end or ban, shown to the person it is about (no shadow decisions). [reason] = server code. */
data class Outcome(val kind: OutcomeKind, val reason: String?, val at: Instant?)

/** Owner's item 8: a verifier or admin corrected the spelling of the name; [from] = the earlier spelling. */
data class NameCorrection(val from: String, val at: Instant?)

data class CommunityStatus(
    val state: MembershipState,
    val communityName: String?,
    val membership: MembershipInfo?,
    val request: OpenRequest?,
    val outcome: Outcome?,
    val nameCorrection: NameCorrection?,
    /** N-24: the name was checked against the college ID; it cannot be changed in the app any more. */
    val nameLocked: Boolean,
    /** A2 (staff screens): shown only from these flags; the server checks every call anyway. */
    val verifier: Boolean,
    val admin: Boolean
)

/** N-25: the code to show an IRL admin, with the ID card, to move an account to a new number. */
data class RelinkCode(val code: String, val expiresInSeconds: Long) {
    override fun toString() = "RelinkCode(<hidden>, expiresInSeconds=$expiresInSeconds)"
}

interface MembershipRepository {
    suspend fun status(): ApiResult<CommunityStatus>
    suspend fun programmes(): ApiResult<List<Programme>>
    suspend fun start(method: VerificationMethod, rollNumber: String, programme: String, graduationYear: Int):
        ApiResult<CommunityStatus>
    suspend fun newCode(): ApiResult<CommunityStatus>
    /** A JPEG already re-encoded on the phone (no metadata); the server re-encodes and encrypts it again. */
    suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<CommunityStatus>
    suspend fun withdraw(): ApiResult<Unit>
    /** Not signed in: the NEW number's sign-up ticket is the credential (used up by the server). */
    suspend fun relink(ticket: String, rollNumber: String): ApiResult<RelinkCode>
}
