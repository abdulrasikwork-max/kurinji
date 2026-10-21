package com.safesphere.app.feature.safetycircle.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit interface for the Safety Circle. It uses the SAME Retrofit/OkHttp client as the login API,
 * so the JWT is attached automatically by AuthInterceptor.
 */
interface SafetyContactApi {

    @GET("api/safety-contacts")
    suspend fun getContacts(): List<SafetyContactDto>

    @GET("api/safety-contacts/{id}")
    suspend fun getContact(@Path("id") id: Long): SafetyContactDto

    @POST("api/safety-contacts")
    suspend fun createContact(@Body body: SafetyContactRequestDto): SafetyContactDto

    @PUT("api/safety-contacts/{id}")
    suspend fun updateContact(@Path("id") id: Long, @Body body: SafetyContactRequestDto): SafetyContactDto

    /** The server answers 204 No Content. */
    @DELETE("api/safety-contacts/{id}")
    suspend fun deleteContact(@Path("id") id: Long)
}
