package com.safesphere.app.feature.auth.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit turns this interface into real HTTP calls. Paths have no leading slash because they are
 * appended to the base URL (BuildConfig.API_BASE_URL). A non-2xx answer throws retrofit2.HttpException.
 *
 * Phase N2: the phone number and a texted code are the only way in (no e-mail, no password).
 */
interface AuthApi {

    /** A code for this number; the same answer whether or not it has an account. */
    @POST("api/auth/phone/start")
    suspend fun startSignIn(@Body body: PhoneStartRequestDto): PhoneChallengeDto

    /** The right code: SIGNED_IN with a session, or SIGN_UP_NEEDED with a ticket. */
    @POST("api/auth/phone/verify")
    suspend fun verifyCode(@Body body: PhoneVerifyRequestDto): VerifyResponseDto

    /** N2: ticket + real name + adult date of birth create the account and sign it in (201). */
    @POST("api/auth/sign-up")
    suspend fun signUp(@Body body: SignUpRequestDto): AuthResponseDto

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
