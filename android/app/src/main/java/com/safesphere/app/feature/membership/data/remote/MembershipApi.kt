package com.safesphere.app.feature.membership.data.remote

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

/** Phase N3: the applicant's side of the community (docs/API.md). The caller is always the token's account. */
interface MembershipApi {

    @GET("api/community/me")
    suspend fun me(): MeDto

    @GET("api/community/programmes")
    suspend fun programmes(): List<ProgrammeDto>

    /** One way only: IN_PERSON (default) or ID_PHOTO. Answers like [me]; an in-person answer carries the code once. */
    @POST("api/community/verification")
    suspend fun start(@Body body: StartRequestDto): MeDto

    /** A new in-person code (the old one stops working). */
    @POST("api/community/verification/code")
    suspend fun newCode(): MeDto

    /** The ID photo (JPEG, made on the phone without metadata); replaces an earlier one. */
    @PUT("api/community/verification/photo")
    suspend fun uploadPhoto(@Body jpeg: RequestBody): MeDto

    /** 204: the request and its photo are gone. */
    @DELETE("api/community/verification")
    suspend fun withdraw()

    /** N-25, not signed in: the new number's sign-up ticket (used up) and the person's roll number. */
    @POST("api/community/relink")
    suspend fun relink(@Body body: RelinkStartDto): RelinkCodeDto
}

data class StartRequestDto(val method: String, val rollNumber: String, val programme: String, val graduationYear: Int) {
    override fun toString() = "StartRequestDto(method=$method, programme=$programme, graduationYear=$graduationYear, " +
        "rollNumber=<hidden>)"
}

data class RelinkStartDto(val ticket: String, val rollNumber: String) {
    override fun toString() = "RelinkStartDto(<hidden>)"
}

data class RelinkCodeDto(val code: String?, val expiresInSeconds: Long?) {
    override fun toString() = "RelinkCodeDto(<hidden>)"
}

data class ProgrammeDto(val code: String?, val label: String?, val level: String?, val firstYear: Int?,
                        val lastYear: Int?)

data class CommunityDto(val code: String?, val name: String?)

data class MembershipDto(
    val programme: String?,
    val programmeLabel: String?,
    val graduationYear: Int?,
    val method: String?,
    val verifiedAt: String?,
    val endsAt: String?,
    val endedAt: String?,
    val endReason: String?
)

data class RequestDto(
    val method: String?,
    val programme: String?,
    val programmeLabel: String?,
    val graduationYear: Int?,
    val code: String?,
    val codeAlive: Boolean?,
    val codeExpiresInSeconds: Long?,
    val photoUploaded: Boolean?,
    val expiresAt: String?
) {
    override fun toString() = "RequestDto(method=$method, code=<hidden>)"
}

data class OutcomeDto(val kind: String?, val reason: String?, val at: String?)

data class NameCorrectionDto(val from: String?, val at: String?)

data class StaffDto(val verifier: Boolean?, val admin: Boolean?)

data class MeDto(
    val state: String?,
    val community: CommunityDto?,
    val membership: MembershipDto?,
    val request: RequestDto?,
    val outcome: OutcomeDto?,
    val nameCorrection: NameCorrectionDto?,
    val nameLocked: Boolean?,
    val staff: StaffDto?
)
