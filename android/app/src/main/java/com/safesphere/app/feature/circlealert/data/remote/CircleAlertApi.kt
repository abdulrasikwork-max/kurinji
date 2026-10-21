package com.safesphere.app.feature.circlealert.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Phase 4D endpoints. Created from the app's single Retrofit instance, so the JWT is attached automatically. */
interface CircleAlertApi {

    @POST("api/emergencies/{id}/circle-alerts")
    suspend fun reportForEmergency(@Path("id") id: Long, @Body body: ReportBatchDto): UploadResultDto

    @POST("api/journeys/{id}/circle-alerts")
    suspend fun reportForJourney(@Path("id") id: Long, @Body body: ReportBatchDto): UploadResultDto

    /** 204 No Content: declared without a return type (see CLAUDE.md gotchas). */
    @POST("api/emergencies/{id}/dial-prompts")
    suspend fun dialPrompt(@Path("id") id: Long)

    @GET("api/emergencies/{id}/context")
    suspend fun ownerContext(@Path("id") id: Long, @Query("audience") audience: String = "OWNER"): OwnerContextDto
}

data class ReportDto(
    val clientRef: String,
    val contactId: Long?,
    val kind: String,
    val channel: String,
    val status: String,
    val occurredAt: String
)

data class ReportBatchDto(val reports: List<ReportDto>)

data class UploadResultDto(val received: Int, val stored: Int, val updated: Int)

// ---- Emergency Context Packet, OWNER projection (only the fields the app shows) ----------------

data class OwnerContextDto(
    val emergency: ContextEmergencyDto,
    val journey: ContextJourneyDto?,
    val latestLocation: ContextLocationDto?,
    val locationCount: Long,
    val highestLevel: Int,
    val escalations: List<ContextEscalationDto>?,
    val circleAlerts: List<ContextAlertDto>?,
    /** Phase 5: counts only (null from an older server). */
    val evidence: ContextEvidenceDto? = null
)

data class ContextEvidenceDto(val items: Long, val sealed: Long, val uploading: Long, val deleted: Long)

data class ContextEmergencyDto(val id: Long, val status: String, val startedAt: String, val endedAt: String?)

data class ContextJourneyDto(val id: Long, val status: String, val destinationLabel: String?)

data class ContextLocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val recordedAt: String,
    val mapLink: String?
) {
    override fun toString(): String = "ContextLocationDto(location=<redacted>, recordedAt=$recordedAt)"
}

data class ContextEscalationDto(val level: Int, val reason: String, val at: String, val source: String)

data class ContextAlertDto(
    val kind: String,
    val channel: String,
    val status: String,
    val contactName: String?,
    val occurredAt: String,
    val source: String
)
