package com.safesphere.app.feature.membership.data.remote

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Phase N3 (A2): verifiers (`/api/verifier/...`, ADMIN or an active verifier) and admins (`/api/admin/community/...`). */
interface StaffApi {

    @POST("api/verifier/lookup")
    suspend fun lookup(@Body body: LookupDto): RequestDetailDto

    @POST("api/verifier/requests/{ref}/decision")
    suspend fun decideInPerson(@Path("ref") ref: String, @Body body: DecisionDto): DecisionResultDto

    @GET("api/admin/community/photo-requests")
    suspend fun photoQueue(): List<RequestDetailDto>

    /** `Cache-Control: no-store`; the bytes go to the screen's memory only. */
    @GET("api/admin/community/requests/{ref}/photo")
    suspend fun photo(@Path("ref") ref: String): ResponseBody

    @POST("api/admin/community/requests/{ref}/decision")
    suspend fun decideByPhoto(@Path("ref") ref: String, @Body body: DecisionDto): DecisionResultDto

    @GET("api/admin/community/verifiers")
    suspend fun verifiers(): List<VerifierDto>

    /** 204. */
    @PUT("api/admin/community/verifiers")
    suspend fun setVerifier(@Body body: VerifierChangeDto)

    /** 204. */
    @PUT("api/admin/community/members/name")
    suspend fun correctName(@Body body: NameChangeDto)

    @GET("api/admin/community/bans")
    suspend fun bans(): List<BanDto>

    @POST("api/admin/community/bans")
    suspend fun ban(@Body body: BanRequestDto): BanDto

    @POST("api/admin/community/bans/{id}/lift")
    suspend fun lift(@Path("id") id: Long): BanDto

    /** 204. */
    @POST("api/admin/community/members/end")
    suspend fun endMembership(@Body body: PhoneDto)

    @POST("api/admin/community/relinks")
    suspend fun relink(@Body body: RelinkDecisionDto): DecisionResultDto

    @GET("api/admin/community/log")
    suspend fun log(@Query("page") page: Int): List<LogDto>
}

data class LookupDto(val code: String) {
    override fun toString() = "LookupDto(<hidden>)"
}

data class DecisionDto(val approve: Boolean, val rollNumber: String?, val name: String?, val reason: String?) {
    override fun toString() = "DecisionDto(approve=$approve, reason=$reason, <hidden>)"
}

data class DecisionResultDto(val outcome: String?, val reason: String?)

data class RequestDetailDto(
    val ref: String?,
    val method: String?,
    val name: String?,
    val phoneHint: String?,
    val programme: String?,
    val programmeLabel: String?,
    val graduationYear: Int?,
    val createdAt: String?,
    val photoUploadedAt: String?
) {
    override fun toString() = "RequestDetailDto(method=$method)"
}

data class VerifierDto(val name: String?, val phoneHint: String?, val programme: String?, val graduationYear: Int?,
                       val since: String?)

data class VerifierChangeDto(val phone: String, val verifier: Boolean) {
    override fun toString() = "VerifierChangeDto(verifier=$verifier, <hidden>)"
}

data class NameChangeDto(val phone: String, val name: String) {
    override fun toString() = "NameChangeDto(<hidden>)"
}

data class BanRequestDto(val phone: String?, val rollNumber: String?, val reason: String, val note: String?) {
    override fun toString() = "BanRequestDto(reason=$reason, <hidden>)"
}

data class BanDto(val id: Long?, val reason: String?, val note: String?, val onRollNumber: Boolean?,
                  val onPhone: Boolean?, val person: String?, val bannedBy: String?, val createdAt: String?,
                  val liftedAt: String?)

data class PhoneDto(val phone: String) {
    override fun toString() = "PhoneDto(<hidden>)"
}

data class RelinkDecisionDto(val code: String, val rollNumber: String) {
    override fun toString() = "RelinkDecisionDto(<hidden>)"
}

data class LogDto(val action: String?, val method: String?, val reason: String?, val actor: String?,
                  val subject: String?, val detail: String?, val at: String?)
