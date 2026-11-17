package com.safesphere.app.feature.moments.data.remote

import com.safesphere.app.core.network.UploadTimeoutInterceptor
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Phase N4: reading moments (Today, one moment, the caller's own, a person's), deleting and keeping. Created from the
 * ONE Retrofit instance in AppContainer, so the sign-in header is attached by AuthInterceptor. Endpoints answering 204
 * have no return type. A2: sharing a moment and its photo (the photo upload alone carries the long upload timeout).
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

    /** The body is a JPEG already re-encoded on the phone (no metadata); the server re-encodes it again. */
    @Headers(UploadTimeoutInterceptor.HEADER_LINE)
    @POST("api/moments/media")
    suspend fun uploadPhoto(@Body body: RequestBody): UploadedPhotoDto

    @DELETE("api/moments/media/{id}")
    suspend fun discardPhoto(@Path("id") mediaId: String)

    @POST("api/moments")
    suspend fun share(@Body body: MomentRequestDto): MomentDto
}
