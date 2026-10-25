package com.safesphere.app.feature.social.domain

import java.time.Instant
import java.time.YearMonth

/**
 * Phase 13B: experiences as the app sees them. No model carries a count, a coordinate, or (for other people's posts) a
 * timestamp: others' posts and perspectives come with a [TimeBand] only.
 */

enum class PostType { EXPERIENCE, ASK }

/** How much of the place others learn. Widest first (edits may only move down the list). */
enum class Precision { EXACT, AREA, CITY, NONE }

/** Who may see a post. Widest first. */
enum class Audience { EVERYONE, CONNECTIONS, ONLY_ME }

enum class CommentPolicy { EVERYONE, CONNECTIONS, NOBODY }

enum class Daypart { MORNING, AFTERNOON, EVENING, NIGHT }

enum class CommentKind { BEEN_HERE, QUESTION, REPLY, ANSWER }

/** When others could first see something: a band, never a time. */
enum class TimeBand { THIS_WEEK, THIS_MONTH, EARLIER }

/** The launch cities (Q2): same codes as the server. */
enum class City {
    CHENNAI, COIMBATORE, MADURAI, TIRUCHIRAPPALLI, SALEM, TIRUNELVELI, VELLORE, ERODE, THOOTHUKUDI, THANJAVUR,
    PUDUCHERRY, BENGALURU, OTHER
}

/** A public place: a name, a category and its ~150 m cell (never a coordinate). */
data class Place(
    val publicId: String,
    val name: String,
    val category: Interest,
    val cell: String,
    val areaCell: String,
    /** Phase 13C: the launch city the server placed it in, or null. */
    val city: City? = null
)

data class MediaRef(val mediaId: String, val width: Int, val height: Int)

data class Post(
    val publicId: String,
    val type: PostType,
    val author: PersonSummary?,
    val own: Boolean,
    val body: String,
    val tip: String?,
    val category: Interest?,
    val precision: Precision,
    /** EXACT only. */
    val placeName: String?,
    /** AREA only (6 characters). */
    val areaCell: String?,
    /** CITY only. */
    val city: City?,
    val experiencedMonth: YearMonth?,
    val daypart: Daypart?,
    val postedPeriod: TimeBand,
    val edited: Boolean,
    val media: List<MediaRef>,
    /** Own posts only. */
    val audience: Audience?,
    val commentPolicy: CommentPolicy,
    val canComment: Boolean,
    /** Own posts only: when others can see it (the delay of an exact-place post). */
    val visibleFrom: Instant?,
    /** Phase 13C: the viewer's OWN Cocoon state for this post (never anyone else's). */
    val yourButterfly: ButterflyState? = null,
    /** Phase 13C: only on the author's own Experiences shared with others (C2). */
    val butterflies: ButterflyTally? = null,
    /** Phase 13C: EXACT only, the place's id (opens its place page). */
    val placeId: String? = null,
    /** Phase 13D (Q6): perspectives from people not connected with the author wait for the author's approval. */
    val approveFirst: Boolean = false,
    /** Phase 13D: own posts only, what a moderator (or a collapse while reviewing) did to it; null = nothing. */
    val ownModeration: OwnModeration? = null,
    /** Phase 13D: other people's posts only: shown behind a tap, and why (never who). */
    val collapsed: Collapsed? = null,
    /** Phase 13E: the automatic check found strong language (a viewer may collapse it on their own phone). */
    val strongLanguage: Boolean = false
)

data class PostPage(val items: List<Post>, val nextCursor: String?)

data class Perspective(
    val publicId: String,
    val kind: CommentKind,
    val author: PersonSummary?,
    val own: Boolean,
    val body: String,
    val postedPeriod: TimeBand,
    /** True only for its writer and the post's author (Q5). */
    val hidden: Boolean,
    val helped: Boolean,
    val parentId: String?,
    val canHide: Boolean,
    val canMarkHelped: Boolean,
    /** Phase 13D (Q6): waiting for the post author's approval (seen by its writer and the post's author only). */
    val awaitingApproval: Boolean = false,
    /** Phase 13D: the post's author may approve it. */
    val canApprove: Boolean = false,
    /** Phase 13D: own perspectives only. */
    val ownModeration: OwnModeration? = null,
    /** Phase 13D: others' perspectives only. */
    val collapsed: Collapsed? = null,
    /** Phase 13E: see Post. */
    val strongLanguage: Boolean = false
)

/** What the person put together in the composer. */
data class PostDraft(
    val type: PostType,
    val body: String,
    val tip: String,
    val category: Interest?,
    val place: Place?,
    val precision: Precision?,
    val city: City?,
    val month: YearMonth?,
    val daypart: Daypart?,
    val audience: Audience?,
    val commentPolicy: CommentPolicy,
    val mediaIds: List<String>,
    /** Phase 13D (Q6): only with "Everyone can add perspectives". */
    val approveFirst: Boolean = false
)

/** A photo uploaded (sanitised) and ready to attach. */
data class UploadedPhoto(val mediaId: String, val width: Int, val height: Int)
