package com.safesphere.app.feature.social.data.remote

import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.ButterflyTally
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.Collapsed
import com.safesphere.app.feature.social.domain.ModerationReason
import com.safesphere.app.feature.social.domain.ModerationSource
import com.safesphere.app.feature.social.domain.ModerationState
import com.safesphere.app.feature.social.domain.OwnModeration
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.MediaRef
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostDraft
import com.safesphere.app.feature.social.domain.PostPage
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.TimeBand
import com.safesphere.app.feature.social.domain.UploadedPhoto
import java.time.Instant
import java.time.YearMonth

// Phase 13B: these classes mirror the JSON of the backend (docs/API.md). They FAIL CLOSED: an unknown code from a newer
// server is read as the most private / least capable value (a post that may not be commented on, a hidden perspective
// stays hidden), never guessed; an item missing what it needs is dropped. Place cells are redacted in toString().

data class PlaceRequestDto(val name: String, val category: String, val cell: String) {
    override fun toString() = "PlaceRequestDto(name=$name, category=$category, cell=<hidden>)"
}

data class PlaceDto(
    val publicId: String? = null,
    val name: String? = null,
    val category: String? = null,
    val cell: String? = null,
    val areaCell: String? = null,
    val city: String? = null
) {
    fun toDomain(): Place? {
        return Place(
            publicId = publicId ?: return null,
            name = name ?: return null,
            category = Interest.fromCode(category ?: return null) ?: return null,
            cell = cell ?: return null,
            areaCell = areaCell ?: return null,
            // Phase 13C: an unknown city is no city (never guessed).
            city = enumOrNull<City>(city)?.takeIf { it != City.OTHER }
        )
    }

    override fun toString() = "PlaceDto(publicId=$publicId, name=$name, cell=<hidden>)"
}

data class UploadedDto(val mediaId: String? = null, val width: Int? = null, val height: Int? = null) {
    fun toDomain(): UploadedPhoto? {
        return UploadedPhoto(mediaId ?: return null, width ?: 0, height ?: 0)
    }
}

data class PostRequestDto(
    val type: String,
    val body: String,
    val tip: String?,
    val category: String?,
    val placeId: String?,
    val precision: String,
    val city: String?,
    val experiencedMonth: String?,
    val daypart: String?,
    val audience: String,
    val commentPolicy: String,
    val mediaIds: List<String>,
    val approveFirst: Boolean = false
) {
    companion object {
        /** Only for a draft that passed ComposerPolicy (precision and audience chosen). */
        fun from(draft: PostDraft): PostRequestDto {
            val precision = requireNotNull(draft.precision)
            val audience = requireNotNull(draft.audience)
            val experience = draft.type == PostType.EXPERIENCE
            val policy = if (audience == Audience.ONLY_ME) CommentPolicy.NOBODY else draft.commentPolicy
            return PostRequestDto(
                type = draft.type.name,
                body = draft.body.trim(),
                tip = if (experience) draft.tip.trim().ifEmpty { null } else null,
                category = draft.category?.name,
                placeId = if (precision == Precision.EXACT || precision == Precision.AREA) draft.place?.publicId else null,
                precision = precision.name,
                city = if (precision == Precision.CITY) draft.city?.name else null,
                experiencedMonth = if (experience) draft.month?.toString() else null,
                daypart = draft.daypart?.name,
                audience = audience.name,
                commentPolicy = policy.name,
                mediaIds = draft.mediaIds,
                // Phase 13D (Q6): the server refuses it with any other comment setting; never sent then.
                approveFirst = draft.approveFirst && policy == CommentPolicy.EVERYONE
            )
        }
    }
}

data class PersonRefDto(val publicId: String? = null, val handle: String? = null, val displayName: String? = null)

data class PostPlaceDto(val publicId: String? = null, val name: String? = null)

data class MediaRefDto(val mediaId: String? = null, val width: Int? = null, val height: Int? = null)

data class PostDto(
    val publicId: String? = null,
    val type: String? = null,
    val author: PersonRefDto? = null,
    val own: Boolean? = null,
    val body: String? = null,
    val tip: String? = null,
    val category: String? = null,
    val precision: String? = null,
    val place: PostPlaceDto? = null,
    val areaCell: String? = null,
    val city: String? = null,
    val experiencedMonth: String? = null,
    val daypart: String? = null,
    val postedPeriod: String? = null,
    val edited: Boolean? = null,
    val media: List<MediaRefDto>? = null,
    val audience: String? = null,
    val commentPolicy: String? = null,
    val canComment: Boolean? = null,
    val visibleFrom: String? = null,
    val yourButterfly: String? = null,
    val butterflies: ButterflyTallyDto? = null,
    val approveFirst: Boolean? = null,
    val moderation: ModerationViewDto? = null,
    val collapsed: String? = null,
    val strongLanguage: Boolean? = null
) {
    fun toDomain(): Post? {
        val author = author?.let { a ->
            val id = a.publicId ?: return@let null
            com.safesphere.app.feature.social.domain.PersonSummary(id, a.handle.orEmpty(), a.displayName.orEmpty())
        }
        return Post(
            publicId = publicId ?: return null,
            type = enumOrNull<PostType>(type) ?: return null,
            author = author,
            own = own == true,
            body = body.orEmpty(),
            tip = tip,
            category = category?.let(Interest::fromCode),
            // Unknown precision: show no place at all.
            precision = enumOrNull<Precision>(precision) ?: Precision.NONE,
            placeName = place?.name,
            areaCell = areaCell,
            city = enumOrNull<City>(city),
            experiencedMonth = experiencedMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
            daypart = enumOrNull<Daypart>(daypart),
            // Unknown band: the vaguest one.
            postedPeriod = enumOrNull<TimeBand>(postedPeriod) ?: TimeBand.EARLIER,
            edited = edited == true,
            media = media.orEmpty().mapNotNull { m -> m.mediaId?.let { MediaRef(it, m.width ?: 0, m.height ?: 0) } },
            audience = enumOrNull<Audience>(audience),
            // Unknown comment setting: the closed one.
            commentPolicy = enumOrNull<CommentPolicy>(commentPolicy) ?: CommentPolicy.NOBODY,
            canComment = canComment == true,
            visibleFrom = visibleFrom?.let { runCatching { Instant.parse(it) }.getOrNull() },
            yourButterfly = enumOrNull<ButterflyState>(yourButterfly),
            // Phase 13C: only the author's own posts carry a tally; anything else, or an inconsistent one, is dropped.
            butterflies = if (own == true) butterflies?.toDomain()?.takeIf { it.isConsistent } else null,
            placeId = if (precision == Precision.EXACT.name) place?.publicId else null,
            approveFirst = approveFirst == true,
            ownModeration = if (own == true) moderation?.toDomain() else null,
            collapsed = if (own == true) null else collapsedOf(collapsed),
            strongLanguage = strongLanguage == true
        )
    }

    override fun toString() = "PostDto(publicId=$publicId, type=$type, precision=$precision)"
}

/**
 * Phase 13D: what happened to one's own post or perspective. FAILS CLOSED: an unknown state reads as HELD (the most
 * careful notice: "hidden while a moderator looks"), an unknown reason as none.
 */
data class ModerationViewDto(
    val state: String? = null,
    val reason: String? = null,
    val decisionId: String? = null,
    val source: String? = null,
    val flagged: List<SpanDto>? = null
) {
    /**
     * Phase 13E: an unknown or missing source reads as MODERATOR (the 13D words, no phrase); flagged phrases only for
     * the automatic check, and only well-formed ones.
     */
    fun toDomain(): OwnModeration? {
        if (state == null) return null
        val by = enumOrNull<ModerationSource>(source) ?: ModerationSource.MODERATOR
        return OwnModeration(enumOrNull<ModerationState>(state) ?: ModerationState.HELD,
            enumOrNull<ModerationReason>(reason), decisionId, by,
            if (by == ModerationSource.SYSTEM) flagged.orEmpty().mapNotNull { it.toRange() } else emptyList())
    }
}

/** Phase 13E: a flagged phrase as code points [start, end) of one's own text. */
data class SpanDto(val start: Int? = null, val end: Int? = null) {
    fun toRange(): IntRange? {
        val s = start ?: return null
        val e = end ?: return null
        return if (s in 0 until e) s until e else null
    }
}

/** Phase 13D: an unknown collapse value is read as UNDER_REVIEW (still behind a tap, never shown open by mistake). */
internal fun collapsedOf(value: String?): Collapsed? =
    if (value == null) null else enumOrNull<Collapsed>(value) ?: Collapsed.UNDER_REVIEW

/** Phase 13C: the author's private tally (C2). */
data class ButterflyTallyDto(val count: Int? = null, val fewerThanThree: Boolean? = null) {
    fun toDomain() = ButterflyTally(count, fewerThanThree != false)
}

data class PostPageDto(val items: List<PostDto>? = null, val nextCursor: String? = null) {
    fun toDomain() = PostPage(items.orEmpty().mapNotNull { it.toDomain() }, nextCursor)
}

data class CommentRequestDto(val kind: String, val body: String, val parentId: String?)

data class CommentDto(
    val publicId: String? = null,
    val kind: String? = null,
    val author: PersonRefDto? = null,
    val own: Boolean? = null,
    val body: String? = null,
    val postedPeriod: String? = null,
    val hidden: Boolean? = null,
    val helped: Boolean? = null,
    val parentId: String? = null,
    val canHide: Boolean? = null,
    val canMarkHelped: Boolean? = null,
    val awaitingApproval: Boolean? = null,
    val canApprove: Boolean? = null,
    val moderation: ModerationViewDto? = null,
    val collapsed: String? = null,
    val strongLanguage: Boolean? = null
) {
    /** An unknown kind is dropped (it may need words this app does not have). */
    fun toDomain(): Perspective? {
        val author = author?.let { a ->
            val id = a.publicId ?: return@let null
            com.safesphere.app.feature.social.domain.PersonSummary(id, a.handle.orEmpty(), a.displayName.orEmpty())
        }
        return Perspective(
            publicId = publicId ?: return null,
            kind = enumOrNull<CommentKind>(kind) ?: return null,
            author = author,
            own = own == true,
            body = body.orEmpty(),
            postedPeriod = enumOrNull<TimeBand>(postedPeriod) ?: TimeBand.EARLIER,
            hidden = hidden == true,
            helped = helped == true,
            parentId = parentId,
            canHide = canHide == true,
            canMarkHelped = canMarkHelped == true,
            awaitingApproval = awaitingApproval == true,
            canApprove = canApprove == true && awaitingApproval == true,
            ownModeration = if (own == true) moderation?.toDomain() else null,
            collapsed = if (own == true) null else collapsedOf(collapsed),
            strongLanguage = strongLanguage == true
        )
    }
}

internal inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    if (name == null) null else enumValues<T>().firstOrNull { it.name == name }
