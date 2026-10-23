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
    val areaCell: String
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
    val visibleFrom: Instant?
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
    val canMarkHelped: Boolean
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
    val mediaIds: List<String>
)

/** A photo uploaded (sanitised) and ready to attach. */
data class UploadedPhoto(val mediaId: String, val width: Int, val height: Int)
