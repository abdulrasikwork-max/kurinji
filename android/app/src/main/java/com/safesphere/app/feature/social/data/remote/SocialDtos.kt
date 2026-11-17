package com.safesphere.app.feature.social.data.remote

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
import java.time.Instant

// Phase 13A: these classes mirror the JSON of the backend (docs/API.md). Unknown codes from a newer server are
// dropped (lists) or read as the most cautious value (relationship NONE), never guessed.

/** Only the part of GET /api/users/me this feature needs. */
data class AgeStatusDto(val ageConfirmed: Boolean? = null)

data class AgeConfirmationDto(val dateOfBirth: String) {
    override fun toString() = "AgeConfirmationDto(<hidden>)"
}

data class ProfileRequestDto(val handle: String, val bio: String?) {
    companion object {
        fun from(input: ProfileInput) = ProfileRequestDto(
            handle = input.handle.trim(),
            bio = input.bio.trim().ifEmpty { null }
        )
    }
}

data class OwnProfileDto(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String? = null,
    val programme: String? = null,
    val graduationYear: Int? = null,
    val newMemberUntil: String? = null,
    val handleChangeAvailableAt: String? = null,
    val photo: String? = null
) {
    fun toDomain() = OwnProfile(
        publicId = publicId,
        handle = handle,
        displayName = displayName,
        bio = bio,
        programme = code(programme),
        graduationYear = year(graduationYear),
        newMemberUntil = newMemberUntil?.let { runCatching { Instant.parse(it) }.getOrNull() },
        handleChangeAvailableAt = handleChangeAvailableAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        photo = photoKey(photo)
    )
}

data class PersonProfileDto(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String? = null,
    val programme: String? = null,
    val graduationYear: Int? = null,
    val relationship: String? = null,
    val photo: String? = null
) {
    fun toDomain() = PersonProfile(
        publicId = publicId,
        handle = handle,
        displayName = displayName,
        bio = bio,
        programme = code(programme),
        graduationYear = year(graduationYear),
        relationship = Relationship.entries.firstOrNull { it.name == relationship } ?: Relationship.NONE,
        photo = photoKey(photo)
    )
}

data class PersonSummaryDto(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val photo: String? = null
) {
    fun toDomain() = PersonSummary(publicId, handle, displayName, photoKey(photo))
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

/** Phase 17E: a photo key is a lower-case UUID; anything else is no photo (never put into a URL). */
fun photoKey(raw: String?): String? = raw?.takeIf { PHOTO_KEY.matches(it) }

/** A programme code as the server writes it (letters, digits, _ and -), or nothing: never free text. */
private fun code(raw: String?): String? = raw?.takeIf { PROGRAMME.matches(it) }

/** A plausible graduation year, or nothing. */
private fun year(raw: Int?): Int? = raw?.takeIf { it in 1990..2100 }

private val PROGRAMME = Regex("^[A-Za-z0-9_-]{1,40}$")
private val PHOTO_KEY = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** A closed-list code of the server as its enum, or null when this app does not know it (never guessed). */
inline fun <reified T : Enum<T>> enumOrNull(raw: String?): T? = enumValues<T>().firstOrNull { it.name == raw }
