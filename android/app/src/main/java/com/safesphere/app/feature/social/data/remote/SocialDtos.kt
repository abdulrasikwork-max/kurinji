package com.safesphere.app.feature.social.data.remote

import com.safesphere.app.feature.social.domain.ConnectResult
import com.safesphere.app.feature.social.domain.ConnectStatus
import com.safesphere.app.feature.social.domain.InviteItem
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Language
import com.safesphere.app.feature.social.domain.NewInvite
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.PersonProfile
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.Relationship
import com.safesphere.app.feature.social.domain.RequestItem
import java.time.Instant

// Phase 13A: these classes mirror the JSON of the backend (docs/API.md). Unknown codes from a newer server are
// dropped (lists) or read as the most cautious value (relationship NONE), never guessed.

/** Only the part of GET /api/users/me this feature needs. */
data class AgeStatusDto(val ageConfirmed: Boolean? = null)

data class AgeConfirmationDto(val dateOfBirth: String) {
    override fun toString() = "AgeConfirmationDto(<hidden>)"
}

data class ProfileRequestDto(
    val handle: String,
    val displayName: String,
    val bio: String?,
    val languages: List<String>,
    val interests: List<String>,
    val city: String?
) {
    companion object {
        fun from(input: ProfileInput) = ProfileRequestDto(
            handle = input.handle.trim(),
            displayName = input.displayName.trim(),
            bio = input.bio.trim().ifEmpty { null },
            languages = input.languages.map { it.code },
            interests = input.interests.map { it.name },
            city = input.city.trim().ifEmpty { null }
        )
    }
}

data class OwnProfileDto(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String? = null,
    val languages: List<String>? = null,
    val interests: List<String>? = null,
    val city: String? = null,
    val handleChangeAvailableAt: String? = null
) {
    fun toDomain() = OwnProfile(
        publicId = publicId,
        handle = handle,
        displayName = displayName,
        bio = bio,
        languages = languages.orEmpty().mapNotNull(Language::fromCode),
        interests = interests.orEmpty().mapNotNull(Interest::fromCode),
        city = city,
        handleChangeAvailableAt = handleChangeAvailableAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    )
}

data class PersonProfileDto(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String? = null,
    val languages: List<String>? = null,
    val interests: List<String>? = null,
    val city: String? = null,
    val relationship: String? = null
) {
    fun toDomain() = PersonProfile(
        publicId = publicId,
        handle = handle,
        displayName = displayName,
        bio = bio,
        languages = languages.orEmpty().mapNotNull(Language::fromCode),
        interests = interests.orEmpty().mapNotNull(Interest::fromCode),
        city = city,
        relationship = Relationship.entries.firstOrNull { it.name == relationship } ?: Relationship.NONE
    )
}

data class PersonSummaryDto(val publicId: String, val handle: String, val displayName: String) {
    fun toDomain() = PersonSummary(publicId, handle, displayName)
}

data class PersonTargetDto(val profileId: String)

data class RequestItemDto(val requestId: String, val person: PersonSummaryDto) {
    fun toDomain() = RequestItem(requestId, person.toDomain())
}

data class ConnectResultDto(val status: String?, val person: PersonSummaryDto) {
    /** Only CONNECTED means connected; anything else is shown as a waiting request. */
    fun toDomain() = ConnectResult(
        if (status == ConnectStatus.CONNECTED.name) ConnectStatus.CONNECTED else ConnectStatus.REQUESTED,
        person.toDomain()
    )
}

data class NewInviteDto(val id: String, val code: String, val expiresAt: String) {
    fun toDomain() = NewInvite(id, code, Instant.parse(expiresAt))
    override fun toString() = "NewInviteDto(id=$id, code=<hidden>)"
}

data class InviteItemDto(val id: String, val expiresAt: String) {
    fun toDomain() = InviteItem(id, Instant.parse(expiresAt))
}

data class RedeemDto(val code: String) {
    override fun toString() = "RedeemDto(<hidden>)"
}
