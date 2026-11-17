package com.safesphere.app.feature.social.domain

import java.time.Instant

/**
 * Phase 13A: profiles and connections, as the app sees them. No model carries a count of anything (connections,
 * requests, look-ups): the platform shows no numbers about people.
 */

/** How the viewer stands with someone. A request the viewer declined shows as NONE; its sender still sees REQUESTED. */
enum class Relationship { SELF, NONE, REQUESTED, REQUEST_RECEIVED, CONNECTED }

data class OwnProfile(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String?,
    /** N4 (N-51): the programme (its code) and graduation year of the verified membership; null without one. */
    val programme: String?,
    val graduationYear: Int?,
    /** The end of the 7-day new-member period; null once established (or without a membership). */
    val newMemberUntil: Instant?,
    /** Set while the handle cannot change yet (once per 30 days). */
    val handleChangeAvailableAt: Instant?,
    /** Phase 17E: the profile photo's key (null = none). */
    val photo: String? = null
)

data class PersonProfile(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String?,
    /** N4 (N-51): from the verified membership, shown to other members. */
    val programme: String?,
    val graduationYear: Int?,
    val relationship: Relationship,
    /** Phase 17E: the profile photo's key (null = none). */
    val photo: String? = null
)

/** A person in a list (connections, requests, blocks, mutes) or the author of a moment. Phase 17E: [photo] key or null. */
data class PersonSummary(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val photo: String? = null
)

data class RequestItem(val requestId: String, val person: PersonSummary)

enum class ConnectStatus { REQUESTED, CONNECTED }

data class ConnectResult(val status: ConnectStatus, val person: PersonSummary)

/** The code is shown ONCE (the server keeps only its hash). */
data class NewInvite(val id: String, val code: String, val expiresAt: Instant) {
    override fun toString() = "NewInvite(id=$id, code=<hidden>, expiresAt=$expiresAt)"
}

data class InviteItem(val id: String, val expiresAt: Instant)

/** What the person typed on the profile screen. */
data class ProfileInput(val handle: String, val bio: String)
