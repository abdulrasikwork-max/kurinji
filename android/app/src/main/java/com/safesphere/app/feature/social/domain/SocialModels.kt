package com.safesphere.app.feature.social.domain

import java.time.Instant

/**
 * Phase 13A: profiles and connections, as the app sees them. No model carries a count of anything (connections,
 * requests, look-ups): the platform shows no numbers about people.
 */

/** Languages a profile can list (closed list, same codes as the server). */
enum class Language(val code: String) {
    TA("ta"), EN("en"), HI("hi"), ML("ml"), TE("te"), KN("kn");

    companion object {
        fun fromCode(code: String): Language? = entries.firstOrNull { it.code == code }
    }
}

/** The experience categories a profile can list as interests (closed list, same codes as the server). */
enum class Interest {
    FOOD_CAFES, NATURE_OUTDOORS, CULTURE_HERITAGE, MUSIC_NIGHTLIFE, STUDY_WORK, DAY_TRIPS, ACTIVITIES_SPORT,
    HOBBIES_MAKING, CITY_LIFE, HIDDEN_GEMS;

    companion object {
        fun fromCode(code: String): Interest? = entries.firstOrNull { it.name == code }
    }
}

/** How the viewer stands with someone. A request the viewer declined shows as NONE; its sender still sees REQUESTED. */
enum class Relationship { SELF, NONE, REQUESTED, REQUEST_RECEIVED, CONNECTED }

data class OwnProfile(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String?,
    val languages: List<Language>,
    val interests: List<Interest>,
    val city: String?,
    /** Set while the handle cannot change yet (once per 30 days). */
    val handleChangeAvailableAt: Instant?
)

data class PersonProfile(
    val publicId: String,
    val handle: String,
    val displayName: String,
    val bio: String?,
    val languages: List<Language>,
    val interests: List<Interest>,
    val city: String?,
    val relationship: Relationship
)

/** A person in a list (connections, requests, blocks, mutes). */
data class PersonSummary(val publicId: String, val handle: String, val displayName: String)

data class RequestItem(val requestId: String, val person: PersonSummary)

enum class ConnectStatus { REQUESTED, CONNECTED }

data class ConnectResult(val status: ConnectStatus, val person: PersonSummary)

/** The code is shown ONCE (the server keeps only its hash). */
data class NewInvite(val id: String, val code: String, val expiresAt: Instant) {
    override fun toString() = "NewInvite(id=$id, code=<hidden>, expiresAt=$expiresAt)"
}

data class InviteItem(val id: String, val expiresAt: Instant)

/** What the person typed on the profile screen. */
data class ProfileInput(
    val handle: String,
    val displayName: String,
    val bio: String,
    val languages: Set<Language>,
    val interests: Set<Interest>,
    val city: String
)
