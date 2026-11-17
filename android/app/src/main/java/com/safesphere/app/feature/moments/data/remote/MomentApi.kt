package com.safesphere.app.feature.moments.data.remote

import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Phase N4: reading moments (Today, one moment, the caller's own, a person's), deleting and keeping. Created from the
 * ONE Retrofit instance in AppContainer, so the sign-in header is attached by AuthInterceptor. Endpoints answering 204
 * have no return type. Sharing a moment and its photo arrive with the Create screen.
 */
interface MomentApi {

    @GET("api/moments/today")
    suspend fun today(@Query("people") people: String, @Query("cursor") cursor: String?): TodayDto

    @GET("api/moments/mine")
    suspend fun mine(): MineDto

    @GET("api/moments/{id}")
    suspend fun moment(@Path("id") id: String): MomentDto

    @GET("api/profiles/{id}/moments")
    suspend fun ofProfile(@Path("id") profileId: String): ProfileMomentsDto

    @DELETE("api/moments/{id}")
    suspend fun delete(@Path("id") id: String)

    @PUT("api/moments/{id}/kept")
    suspend fun keep(@Path("id") id: String): MomentDto

    @DELETE("api/moments/{id}/kept")
    suspend fun unkeep(@Path("id") id: String)
}
