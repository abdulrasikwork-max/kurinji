package com.safesphere.app.feature.social.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Phase 13A: profiles, connections, invite codes, blocks and mutes. Created from the ONE Retrofit instance in
 * AppContainer, so the JWT is attached by AuthInterceptor. Endpoints answering 204 have no return type.
 */
interface SocialApi {

    @GET("api/users/me")
    suspend fun me(): AgeStatusDto

    @POST("api/users/me/age-confirmation")
    suspend fun confirmAge(@Body body: AgeConfirmationDto)

    @GET("api/profiles/me")
    suspend fun ownProfile(): OwnProfileDto

    @PUT("api/profiles/me")
    suspend fun saveProfile(@Body body: ProfileRequestDto): OwnProfileDto

    @GET("api/profiles/by-handle/{handle}")
    suspend fun byHandle(@Path("handle") handle: String): PersonProfileDto

    @GET("api/profiles/{id}")
    suspend fun byId(@Path("id") publicId: String): PersonProfileDto

    @POST("api/connections/requests")
    suspend fun connect(@Body body: PersonTargetDto): ConnectResultDto

    @GET("api/connections/requests")
    suspend fun requests(@Query("direction") direction: String): List<RequestItemDto>

    @POST("api/connections/requests/{id}/accept")
    suspend fun accept(@Path("id") requestId: String): ConnectResultDto

    @POST("api/connections/requests/{id}/decline")
    suspend fun decline(@Path("id") requestId: String)

    @DELETE("api/connections/requests/{id}")
    suspend fun cancel(@Path("id") requestId: String)

    @GET("api/connections")
    suspend fun connections(): List<PersonSummaryDto>

    @DELETE("api/connections/{id}")
    suspend fun removeConnection(@Path("id") publicId: String)

    @POST("api/connections/invites")
    suspend fun createInvite(): NewInviteDto

    @GET("api/connections/invites")
    suspend fun invites(): List<InviteItemDto>

    @DELETE("api/connections/invites/{id}")
    suspend fun revokeInvite(@Path("id") inviteId: String)

    @POST("api/connections/invites/redeem")
    suspend fun redeem(@Body body: RedeemDto): ConnectResultDto

    @POST("api/blocks")
    suspend fun block(@Body body: PersonTargetDto)

    @DELETE("api/blocks/{id}")
    suspend fun unblock(@Path("id") publicId: String)

    @GET("api/blocks")
    suspend fun blocked(): List<PersonSummaryDto>

    @POST("api/mutes")
    suspend fun mute(@Body body: PersonTargetDto)

    @DELETE("api/mutes/{id}")
    suspend fun unmute(@Path("id") publicId: String)

    @GET("api/mutes")
    suspend fun muted(): List<PersonSummaryDto>
}
