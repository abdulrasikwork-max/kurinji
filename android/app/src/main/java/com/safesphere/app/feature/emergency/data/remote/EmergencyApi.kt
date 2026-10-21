package com.safesphere.app.feature.emergency.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Same Retrofit/OkHttp client as the other APIs, so the JWT is attached automatically. */
interface EmergencyApi {

    @POST("api/emergencies")
    suspend fun start(@Body body: CreateEmergencyRequestDto): EmergencyEventDto

    @GET("api/emergencies")
    suspend fun getHistory(): List<EmergencyEventDto>

    @PUT("api/emergencies/{id}/cancel")
    suspend fun cancel(@Path("id") id: Long): EmergencyEventDto

    @PUT("api/emergencies/{id}/resolve")
    suspend fun resolve(@Path("id") id: Long): EmergencyEventDto

    /** Phase 4A: stores 1..50 location points with the person's own emergency (owner only). */
    @POST("api/emergencies/{id}/locations")
    suspend fun uploadLocations(@Path("id") id: Long, @Body body: LocationBatchRequestDto): LocationUploadResponseDto
}
