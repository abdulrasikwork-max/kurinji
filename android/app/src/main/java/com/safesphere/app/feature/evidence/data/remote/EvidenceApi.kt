package com.safesphere.app.feature.evidence.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Phase 5 Evidence Vault. Same Retrofit/OkHttp client as every other API, so the JWT is attached automatically. */
interface EvidenceApi {

    @GET("api/evidence/vault-key")
    suspend fun vaultKey(): VaultKeyDto

    @POST("api/emergencies/{id}/evidence")
    suspend fun declare(@Path("id") emergencyId: Long, @Body body: DeclareEvidenceDto): EvidenceDto

    @GET("api/emergencies/{id}/evidence")
    suspend fun list(@Path("id") emergencyId: Long): List<EvidenceDto>

    @GET("api/emergencies/{id}/evidence/custody")
    suspend fun custody(@Path("id") emergencyId: Long): CustodyReportDto

    @GET("api/evidence/{id}")
    suspend fun get(@Path("id") evidenceId: Long): EvidenceDto

    /** One piece of ciphertext (application/octet-stream), streamed from the file. */
    @PUT("api/evidence/{id}/content")
    suspend fun upload(@Path("id") evidenceId: Long, @Query("offset") offset: Long, @Body body: RequestBody): EvidenceDto

    @POST("api/evidence/{id}/seal")
    suspend fun seal(@Path("id") evidenceId: Long): EvidenceDto

    @POST("api/evidence/{id}/verify")
    suspend fun verify(@Path("id") evidenceId: Long): VerifyEvidenceDto

    /** Streamed: a large video is never held in memory. */
    @Streaming
    @GET("api/evidence/{id}/content")
    suspend fun download(@Path("id") evidenceId: Long): ResponseBody

    @POST("api/evidence/{id}/delete")
    suspend fun delete(@Path("id") evidenceId: Long, @Body body: DeleteEvidenceDto): EvidenceDto

    // ---- restore on a new phone (V11) ----

    @GET("api/evidence/recovery")
    suspend fun recoveryStatus(): RecoveryStatusDto

    @PUT("api/evidence/recovery")
    suspend fun setRecovery(@Body body: SetRecoveryDto): RecoveryStatusDto

    @POST("api/evidence/recovery/envelope")
    suspend fun recoveryEnvelope(@Body body: RecoveryPasswordDto): RecoveryEnvelopeDto

    @GET("api/evidence/recovery/items")
    suspend fun recoverableItems(@Query("page") page: Int): RecoverableItemsPageDto

    @PUT("api/evidence/{id}/recovery-key")
    suspend fun attachRecoveryKey(@Path("id") evidenceId: Long, @Body body: AttachRecoveryKeyDto): EvidenceDto
}
