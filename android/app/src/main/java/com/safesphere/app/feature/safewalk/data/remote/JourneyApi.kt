package com.safesphere.app.feature.safewalk.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** /api/journeys ("On my way"; N6-B1: no location, no escalation). Created from the app's single Retrofit instance, so the JWT is attached automatically. */
interface JourneyApi {

    @POST("api/journeys")
    suspend fun start(@Body body: StartJourneyRequestDto): JourneyDto

    @GET("api/journeys")
    suspend fun getHistory(): List<JourneyDto>

    @POST("api/journeys/{id}/check-ins")
    suspend fun checkIn(@Path("id") id: Long): JourneyDto

    @PUT("api/journeys/{id}/complete")
    suspend fun complete(@Path("id") id: Long): JourneyDto

    @PUT("api/journeys/{id}/cancel")
    suspend fun cancel(@Path("id") id: Long): JourneyDto
}
