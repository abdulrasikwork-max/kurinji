package com.safesphere.app.feature.circlealert.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** N6 (D20): the server's automatic Safety Circle texts. From the app's single Retrofit instance (JWT attached). */
interface SafetyAlertApi {

    @GET("api/safety-alerts")
    suspend fun settings(): AlertSettingsDto

    @PUT("api/safety-alerts")
    suspend fun choose(@Body body: AlertChoiceDto): AlertSettingsDto

    @POST("api/safety-alerts/test")
    suspend fun test(): AlertSettingsDto

    @GET("api/emergencies/{id}/alerts")
    suspend fun forEmergency(@Path("id") id: Long): EmergencyAlertsDto
}

data class AlertChoiceDto(val on: Boolean)

/** Every field nullable: an answer this app does not fully understand is read fail-closed. */
data class AlertSettingsDto(
    val choice: String?,
    val preview: String?,
    val contacts: List<ContactPlanDto>?,
    val lastTest: TestDto?,
    val testAvailable: Boolean?
)

data class ContactPlanDto(val contactId: Long?, val name: String?, val automatic: Boolean?, val reason: String?)

data class TestDto(val status: String?, val at: String?)

data class EmergencyAlertsDto(val choice: String?, val alerts: List<AlertStateDto>?)

data class AlertStateDto(
    val contactId: Long?,
    val contactName: String?,
    val kind: String?,
    val status: String?,
    val reason: String?,
    val at: String?,
    val late: Boolean?
)
