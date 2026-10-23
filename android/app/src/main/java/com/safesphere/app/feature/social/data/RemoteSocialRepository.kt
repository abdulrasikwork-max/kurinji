package com.safesphere.app.feature.social.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.data.remote.AgeConfirmationDto
import com.safesphere.app.feature.social.data.remote.PersonTargetDto
import com.safesphere.app.feature.social.data.remote.ProfileRequestDto
import com.safesphere.app.feature.social.data.remote.RedeemDto
import com.safesphere.app.feature.social.data.remote.SocialApi
import com.safesphere.app.feature.social.domain.ConnectResult
import com.safesphere.app.feature.social.domain.HandlePolicy
import com.safesphere.app.feature.social.domain.InviteItem
import com.safesphere.app.feature.social.domain.NewInvite
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.PersonProfile
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.RequestItem
import com.safesphere.app.feature.social.domain.SocialRepository
import java.time.LocalDate

/** Phase 13A: the social features on the backend. All error handling is in [ApiCallHandler]. Nothing is cached. */
class RemoteSocialRepository(
    private val api: SocialApi,
    private val handler: ApiCallHandler
) : SocialRepository {

    /** An old server without the field is treated as "confirmed": the server still refuses if it is not. */
    override suspend fun ageConfirmed(): ApiResult<Boolean> = handler.execute { api.me().ageConfirmed ?: true }

    override suspend fun confirmAge(dateOfBirth: LocalDate): ApiResult<Unit> =
        handler.execute { api.confirmAge(AgeConfirmationDto(dateOfBirth.toString())) }

    override suspend fun ownProfile(): ApiResult<OwnProfile?> =
        when (val result = handler.execute { api.ownProfile().toDomain() }) {
            is ApiResult.Success -> result
            is ApiResult.Failure -> if (result.error == ApiError.NOT_FOUND) ApiResult.Success(null) else result
        }

    override suspend fun saveProfile(input: ProfileInput): ApiResult<OwnProfile> =
        handler.execute { api.saveProfile(ProfileRequestDto.from(input)).toDomain() }

    override suspend fun findByHandle(handle: String): ApiResult<PersonProfile> =
        handler.execute { api.byHandle(HandlePolicy.normalize(handle)).toDomain() }

    override suspend fun profile(publicId: String): ApiResult<PersonProfile> =
        handler.execute { api.byId(publicId).toDomain() }

    override suspend fun connect(publicId: String): ApiResult<ConnectResult> =
        handler.execute { api.connect(PersonTargetDto(publicId)).toDomain() }

    override suspend fun incomingRequests(): ApiResult<List<RequestItem>> =
        handler.execute { api.requests("incoming").map { it.toDomain() } }

    override suspend fun outgoingRequests(): ApiResult<List<RequestItem>> =
        handler.execute { api.requests("outgoing").map { it.toDomain() } }

    override suspend fun accept(requestId: String): ApiResult<ConnectResult> =
        handler.execute { api.accept(requestId).toDomain() }

    override suspend fun decline(requestId: String): ApiResult<Unit> = handler.execute { api.decline(requestId) }

    override suspend fun cancel(requestId: String): ApiResult<Unit> = handler.execute { api.cancel(requestId) }

    override suspend fun connections(): ApiResult<List<PersonSummary>> =
        handler.execute { api.connections().map { it.toDomain() } }

    override suspend fun removeConnection(publicId: String): ApiResult<Unit> =
        handler.execute { api.removeConnection(publicId) }

    override suspend fun createInvite(): ApiResult<NewInvite> = handler.execute { api.createInvite().toDomain() }

    override suspend fun invites(): ApiResult<List<InviteItem>> = handler.execute { api.invites().map { it.toDomain() } }

    override suspend fun revokeInvite(id: String): ApiResult<Unit> = handler.execute { api.revokeInvite(id) }

    override suspend fun redeem(code: String): ApiResult<ConnectResult> =
        handler.execute { api.redeem(RedeemDto(code.trim())).toDomain() }

    override suspend fun block(publicId: String): ApiResult<Unit> = handler.execute { api.block(PersonTargetDto(publicId)) }

    override suspend fun unblock(publicId: String): ApiResult<Unit> = handler.execute { api.unblock(publicId) }

    override suspend fun blocked(): ApiResult<List<PersonSummary>> = handler.execute { api.blocked().map { it.toDomain() } }

    override suspend fun mute(publicId: String): ApiResult<Unit> = handler.execute { api.mute(PersonTargetDto(publicId)) }

    override suspend fun unmute(publicId: String): ApiResult<Unit> = handler.execute { api.unmute(publicId) }

    override suspend fun muted(): ApiResult<List<PersonSummary>> = handler.execute { api.muted().map { it.toDomain() } }
}
