package com.safesphere.app.feature.social.domain

import com.safesphere.app.core.network.ApiResult
import java.time.LocalDate

/**
 * Phase 13A: profiles, connections, invite codes, blocks and mutes on the backend. Nothing is stored on the phone:
 * every screen loads what it shows when it opens.
 */
interface SocialRepository {

    /** Whether this account has declared an adult date of birth (accounts created before 13A confirm once). */
    suspend fun ageConfirmed(): ApiResult<Boolean>

    suspend fun confirmAge(dateOfBirth: LocalDate): ApiResult<Unit>

    /** The caller's profile, or Success(null) when they have not made one yet. */
    suspend fun ownProfile(): ApiResult<OwnProfile?>

    suspend fun saveProfile(input: ProfileInput): ApiResult<OwnProfile>

    suspend fun findByHandle(handle: String): ApiResult<PersonProfile>

    suspend fun profile(publicId: String): ApiResult<PersonProfile>

    /** Asks to connect; connects at once when the other person had asked first. */
    suspend fun connect(publicId: String): ApiResult<ConnectResult>

    suspend fun incomingRequests(): ApiResult<List<RequestItem>>

    suspend fun outgoingRequests(): ApiResult<List<RequestItem>>

    suspend fun accept(requestId: String): ApiResult<ConnectResult>

    suspend fun decline(requestId: String): ApiResult<Unit>

    suspend fun cancel(requestId: String): ApiResult<Unit>

    suspend fun connections(): ApiResult<List<PersonSummary>>

    suspend fun removeConnection(publicId: String): ApiResult<Unit>

    suspend fun createInvite(): ApiResult<NewInvite>

    suspend fun invites(): ApiResult<List<InviteItem>>

    suspend fun revokeInvite(id: String): ApiResult<Unit>

    suspend fun redeem(code: String): ApiResult<ConnectResult>

    suspend fun block(publicId: String): ApiResult<Unit>

    suspend fun unblock(publicId: String): ApiResult<Unit>

    suspend fun blocked(): ApiResult<List<PersonSummary>>

    suspend fun mute(publicId: String): ApiResult<Unit>

    suspend fun unmute(publicId: String): ApiResult<Unit>

    suspend fun muted(): ApiResult<List<PersonSummary>>
}
