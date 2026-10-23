package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.ConnectResult
import com.safesphere.app.feature.social.domain.ConnectStatus
import com.safesphere.app.feature.social.domain.InviteItem
import com.safesphere.app.feature.social.domain.NewInvite
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.PersonProfile
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.Relationship
import com.safesphere.app.feature.social.domain.RequestItem
import com.safesphere.app.feature.social.domain.SocialRepository
import java.time.Instant
import java.time.LocalDate

/** Phase 13A: an in-memory server for the social ViewModel tests. Each answer can be replaced; calls are recorded. */
class FakeSocialRepository : SocialRepository {

    var ageConfirmedResult: ApiResult<Boolean> = ApiResult.Success(true)
    var ownProfileResult: ApiResult<OwnProfile?> = ApiResult.Success(ME)
    var saveResult: ApiResult<OwnProfile>? = null
    var personResult: ApiResult<PersonProfile> = ApiResult.Success(person(Relationship.NONE))
    var connectResult: ApiResult<ConnectResult> = ApiResult.Success(ConnectResult(ConnectStatus.REQUESTED, BALA))
    var incoming: MutableList<RequestItem> = mutableListOf()
    var outgoing: MutableList<RequestItem> = mutableListOf()
    var connections: MutableList<PersonSummary> = mutableListOf()
    var invites: MutableList<InviteItem> = mutableListOf()
    var blocked: MutableList<PersonSummary> = mutableListOf()
    var muted: MutableList<PersonSummary> = mutableListOf()
    var redeemResult: ApiResult<ConnectResult> = ApiResult.Success(ConnectResult(ConnectStatus.CONNECTED, BALA))
    var createInviteResult: ApiResult<NewInvite> =
        ApiResult.Success(NewInvite("invite-1", "0123456789ABCDEFGHJKMNPQRS", Instant.parse("2026-10-29T10:00:00Z")))
    /** When set, every changing call fails with it. */
    var actionFailure: ApiResult.Failure? = null

    val calls = mutableListOf<String>()
    var confirmedAge: LocalDate? = null
        private set
    var savedInput: ProfileInput? = null
        private set
    var lookedUpHandle: String? = null
        private set
    var redeemedCode: String? = null
        private set

    override suspend fun ageConfirmed() = ageConfirmedResult.also { calls += "ageConfirmed" }

    override suspend fun confirmAge(dateOfBirth: LocalDate): ApiResult<Unit> = act("confirmAge") {
        confirmedAge = dateOfBirth
        ageConfirmedResult = ApiResult.Success(true)
        ownProfileResult = ApiResult.Success(null)
    }

    override suspend fun ownProfile() = ownProfileResult.also { calls += "ownProfile" }

    override suspend fun saveProfile(input: ProfileInput): ApiResult<OwnProfile> {
        calls += "saveProfile"
        savedInput = input
        return saveResult ?: ApiResult.Success(ME.copy(handle = input.handle, displayName = input.displayName))
    }

    override suspend fun findByHandle(handle: String): ApiResult<PersonProfile> {
        calls += "findByHandle"
        lookedUpHandle = handle
        return personResult
    }

    override suspend fun profile(publicId: String) = personResult.also { calls += "profile" }

    override suspend fun connect(publicId: String): ApiResult<ConnectResult> {
        calls += "connect:$publicId"
        actionFailure?.let { return it }
        val result = connectResult
        if (result is ApiResult.Success) {
            personResult = ApiResult.Success(person(
                if (result.data.status == ConnectStatus.CONNECTED) Relationship.CONNECTED else Relationship.REQUESTED))
        }
        return result
    }

    override suspend fun incomingRequests(): ApiResult<List<RequestItem>> = ApiResult.Success(incoming.toList())

    override suspend fun outgoingRequests(): ApiResult<List<RequestItem>> = ApiResult.Success(outgoing.toList())

    override suspend fun accept(requestId: String): ApiResult<ConnectResult> {
        calls += "accept:$requestId"
        actionFailure?.let { return it }
        val item = incoming.first { it.requestId == requestId }
        incoming.remove(item)
        connections.add(item.person)
        return ApiResult.Success(ConnectResult(ConnectStatus.CONNECTED, item.person))
    }

    override suspend fun decline(requestId: String) = act("decline:$requestId") { incoming.removeAll { it.requestId == requestId } }

    override suspend fun cancel(requestId: String) = act("cancel:$requestId") { outgoing.removeAll { it.requestId == requestId } }

    override suspend fun connections(): ApiResult<List<PersonSummary>> = ApiResult.Success(connections.toList())

    override suspend fun removeConnection(publicId: String) = act("remove:$publicId") {
        connections.removeAll { it.publicId == publicId }
        personResult = ApiResult.Success(person(Relationship.NONE))
    }

    override suspend fun createInvite(): ApiResult<NewInvite> {
        calls += "createInvite"
        val result = createInviteResult
        if (result is ApiResult.Success) invites.add(InviteItem(result.data.id, result.data.expiresAt))
        return result
    }

    override suspend fun invites(): ApiResult<List<InviteItem>> = ApiResult.Success(invites.toList())

    override suspend fun revokeInvite(id: String) = act("revoke:$id") { invites.removeAll { it.id == id } }

    override suspend fun redeem(code: String): ApiResult<ConnectResult> {
        calls += "redeem"
        redeemedCode = code
        return redeemResult
    }

    override suspend fun block(publicId: String) = act("block:$publicId") {
        personResult = ApiResult.Failure(ApiError.NOT_FOUND)
    }

    override suspend fun unblock(publicId: String) = act("unblock:$publicId") { blocked.removeAll { it.publicId == publicId } }

    override suspend fun blocked(): ApiResult<List<PersonSummary>> = ApiResult.Success(blocked.toList())

    override suspend fun mute(publicId: String) = act("mute:$publicId") {}

    override suspend fun unmute(publicId: String) = act("unmute:$publicId") { muted.removeAll { it.publicId == publicId } }

    override suspend fun muted(): ApiResult<List<PersonSummary>> = ApiResult.Success(muted.toList())

    private fun act(name: String, change: () -> Unit): ApiResult<Unit> {
        calls += name
        actionFailure?.let { return it }
        change()
        return ApiResult.Success(Unit)
    }

    companion object {
        val ME = OwnProfile("me-id", "asha_k", "Asha", null, emptyList(), emptyList(), null, null)
        val BALA = PersonSummary("bala-id", "bala_r", "Bala")

        fun person(relationship: Relationship) =
            PersonProfile("bala-id", "bala_r", "Bala", "Hello", emptyList(), emptyList(), "Chennai", relationship)
    }
}
