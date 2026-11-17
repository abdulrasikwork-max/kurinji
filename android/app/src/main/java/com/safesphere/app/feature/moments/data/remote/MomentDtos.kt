package com.safesphere.app.feature.moments.data.remote

import com.safesphere.app.feature.moments.domain.DailyQuestion
import com.safesphere.app.feature.moments.domain.DayPart
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentAudience
import com.safesphere.app.feature.moments.domain.MomentAuthor
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentNotice
import com.safesphere.app.feature.moments.domain.MomentPhoto
import com.safesphere.app.feature.moments.domain.MomentPlan
import com.safesphere.app.feature.moments.domain.MomentStatus
import com.safesphere.app.feature.moments.domain.MyMoments
import com.safesphere.app.feature.moments.domain.NoticeState
import com.safesphere.app.feature.moments.domain.ProfileMoments
import com.safesphere.app.feature.moments.domain.RoughDay
import com.safesphere.app.feature.moments.domain.RoughTime
import com.safesphere.app.feature.moments.domain.TodayPage
import java.time.Instant

// Phase N4: these classes mirror the JSON of the backend (docs/API.md, "Phase N4"). A moment whose id, kind, audience
// or status this app does not know is DROPPED from a list (never guessed, never shown half-understood); an unknown
// notice state is read as the most cautious one (HELD: "others cannot see this").

data class MomentAuthorDto(
    val profileId: String? = null,
    val handle: String? = null,
    val name: String? = null,
    val photo: String? = null
)

data class MomentPhotoDto(val width: Int? = null, val height: Int? = null)

data class MomentPlanDto(val at: String? = null, val where: String? = null)

data class QuestionDto(val id: String? = null, val text: String? = null) {
    fun toDomain(): DailyQuestion? {
        val safeId = uuid(id) ?: return null
        val words = text?.trim().orEmpty()
        return if (words.isEmpty()) null else DailyQuestion(safeId, words)
    }
}

data class WhenDto(val day: String? = null, val part: String? = null)

data class NoticeDto(
    val state: String? = null,
    val reason: String? = null,
    val by: String? = null,
    val reviewable: Boolean? = null,
    val decisionId: String? = null
)

data class MomentDto(
    val id: String? = null,
    val kind: String? = null,
    val audience: String? = null,
    val author: MomentAuthorDto? = null,
    val mine: Boolean? = null,
    val text: String? = null,
    val photo: MomentPhotoDto? = null,
    val plan: MomentPlanDto? = null,
    val question: QuestionDto? = null,
    val `when`: WhenDto? = null,
    val status: String? = null,
    val kept: Boolean? = null,
    val collapsed: Boolean? = null,
    val notice: NoticeDto? = null,
    val supportSuggested: Boolean? = null,
    val visibleAt: String? = null,
    val expiresAt: String? = null
) {
    /** Null when this app cannot be sure what it is looking at. */
    fun toDomain(): Moment? {
        val safeId = uuid(id) ?: return null
        val kind = MomentKind.entries.firstOrNull { it.name == kind } ?: return null
        val audience = MomentAudience.entries.firstOrNull { it.name == audience } ?: return null
        val status = MomentStatus.entries.firstOrNull { it.name == status } ?: return null
        val name = author?.name?.trim().orEmpty()
        if (name.isEmpty()) return null
        val photo = photo?.let { p ->
            val w = p.width ?: 0
            val h = p.height ?: 0
            if (w > 0 && h > 0) MomentPhoto(w, h) else null
        }
        val words = text?.takeIf { it.isNotBlank() }
        if (words == null && photo == null) return null
        return Moment(
            id = safeId,
            kind = kind,
            audience = audience,
            author = MomentAuthor(uuid(author?.profileId), author?.handle?.takeIf { it.isNotBlank() }, name,
                uuid(author?.photo)),
            mine = mine == true,
            text = words,
            photo = photo,
            plan = if (kind == MomentKind.WHOS_IN) plan?.let { p ->
                val at = instant(p.at)
                val where = p.where?.trim()?.takeIf { it.isNotEmpty() }
                if (at == null && where == null) null else MomentPlan(at, where)
            } else null,
            question = question?.toDomain(),
            shared = `when`?.let { w ->
                RoughDay.entries.firstOrNull { it.name == w.day }
                    ?.let { day -> RoughTime(day, DayPart.entries.firstOrNull { it.name == w.part }) }
            },
            status = status,
            kept = kept == true,
            collapsed = collapsed == true,
            notice = notice?.let { n ->
                MomentNotice(
                    state = NoticeState.entries.firstOrNull { it.name == n.state } ?: NoticeState.HELD,
                    reason = n.reason?.takeIf { CODE.matches(it) },
                    byPerson = when (n.by) {
                        "MODERATOR" -> true
                        "SYSTEM" -> false
                        else -> null
                    },
                    reviewable = n.reviewable == true,
                    decisionId = uuid(n.decisionId)
                )
            },
            supportSuggested = supportSuggested == true,
            visibleAt = instant(visibleAt),
            expiresAt = instant(expiresAt)
        )
    }
}

data class TodayDto(
    val question: QuestionDto? = null,
    val moments: List<MomentDto>? = null,
    val nextCursor: String? = null
) {
    fun toDomain() = TodayPage(question?.toDomain(), moments.orEmpty().mapNotNull { it.toDomain() }, uuid(nextCursor))
}

data class MineDto(
    val current: List<MomentDto>? = null,
    val kept: List<MomentDto>? = null,
    val keptMax: Int? = null
) {
    fun toDomain() = MyMoments(current.orEmpty().mapNotNull { it.toDomain() }, kept.orEmpty().mapNotNull { it.toDomain() },
        keptMax?.takeIf { it > 0 } ?: DEFAULT_KEPT_MAX)

    companion object {
        const val DEFAULT_KEPT_MAX = 6
    }
}

data class ProfileMomentsDto(val live: List<MomentDto>? = null, val kept: List<MomentDto>? = null) {
    fun toDomain() = ProfileMoments(live.orEmpty().mapNotNull { it.toDomain() }, kept.orEmpty().mapNotNull { it.toDomain() })
}

/** An id is a lower-case UUID; anything else is treated as absent (and never put into a URL). */
fun uuid(raw: String?): String? = raw?.takeIf { UUID.matches(it) }

private fun instant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }

private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val CODE = Regex("^[A-Z_]{1,30}$")
