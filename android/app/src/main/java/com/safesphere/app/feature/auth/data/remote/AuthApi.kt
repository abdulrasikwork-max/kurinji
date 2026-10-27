package com.safesphere.app.feature.auth.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit turns this interface into real HTTP calls. Paths have no leading slash because they are
 * appended to the base URL (BuildConfig.API_BASE_URL). A non-2xx answer throws retrofit2.HttpException.
 */
interface AuthApi {

    /** Phase 17B: 201 with no session; a code goes to the phone and the answer is the verification to finish. */
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): PhoneChallengeDto

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    /** Phase 17B: e-mail + password of an account without a verified phone. */
    @POST("api/auth/phone/start")
    suspend fun startPhoneVerification(@Body body: PhoneStartRequestDto): PhoneChallengeDto

    /** Phase 17B: the right code signs in (the same answer as a sign-in). */
    @POST("api/auth/phone/verify")
    suspend fun verifyPhone(@Body body: PhoneVerifyRequestDto): AuthResponseDto

    @POST("api/auth/phone/resend")
    suspend fun resendCode(@Body body: PhoneResendRequestDto): PhoneChallengeDto

    @POST("api/auth/phone/change")
    suspend fun changePhone(@Body body: PhoneChangeRequestDto): PhoneChallengeDto

    /** Needs the JWT; AuthInterceptor adds the Authorization header. */
    @GET("api/users/me")
    suspend fun me(): UserDto

    /**
     * Phase 11 C1: a new access token and a NEW refresh token (the old one stops working). Public: the refresh token is
     * the credential, so this works however long ago the access token expired. 401 + WWW-Authenticate reason if refused.
     */
    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): AuthResponseDto

    /** Phase 11 C1: ends this phone's sign-in on the server. 204 whatever happened. */
    @POST("api/auth/sign-out")
    suspend fun signOut(@Body body: RefreshRequestDto)
}
