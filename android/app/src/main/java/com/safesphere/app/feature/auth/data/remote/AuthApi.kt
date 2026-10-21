package com.safesphere.app.feature.auth.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit turns this interface into real HTTP calls. Paths have no leading slash because they are
 * appended to the base URL (BuildConfig.API_BASE_URL). A non-2xx answer throws retrofit2.HttpException.
 */
interface AuthApi {

    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): AuthResponseDto

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    /** Needs the JWT; AuthInterceptor adds the Authorization header. */
    @GET("api/users/me")
    suspend fun me(): UserDto
}
