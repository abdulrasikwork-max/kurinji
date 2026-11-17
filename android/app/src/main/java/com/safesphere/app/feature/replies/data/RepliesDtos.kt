package com.safesphere.app.feature.replies.data

import com.safesphere.app.feature.moments.data.remote.MomentPlanDto
import com.safesphere.app.feature.moments.data.remote.NoticeDto
import com.safesphere.app.feature.moments.data.remote.PersonDto
import com.safesphere.app.feature.moments.data.remote.uuid
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentPlan
import com.safesphere.app.feature.replies.domain.Conversation
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.Message
import com.safesphere.app.feature.replies.domain.MessageEvent
import com.safesphere.app.feature.replies.domain.MessageKind
import com.safesphere.app.feature.replies.domain.MomentBrief
import com.safesphere.app.feature.replies.domain.PrivateReplies
import com.safesphere.app.feature.replies.domain.RepliesPage
import com.safesphere.app.feature.replies.domain.ReplyItem
import com.safesphere.app.feature.replies.domain.ReplyType
import com.safesphere.app.feature.replies.domain.Turn
import com.safesphere.app.feature.replies.domain.You
import java.time.Instant

// Phase N5: plans, private replies and Replies as the backend sends them (docs/API.md "Phase N5"). Read fail-closed:
// a conversation of an unknown kind or about an unreadable moment is an error (never shown half-understood); an unknown
// turn is CLOSED (nothing can be sent); an unknown message kind or event is dropped; an unknown Replies line is
// dropped; someone else's notice is never kept (it belongs to the writer).

data class MomentRefDto(
    val id: String? = null,
    val kind: String? = null,
    val text: String? = null,
    val plan: MomentPlanDto? = null,
    val calledOff: Boolean? = null,
    val mine: Boolean? = null,
    val authorName: String? = null,
    val live: Boolean? = null
) {
    fun toDomain(): MomentBrief? {
        val safeId = uuid(id) ?: return null
        val kind = MomentKind.entries.firstOrNull { it.name == kind } ?: return null
        // Someone else's moment needs its author's name; one's own comes without it (`authorName: null` for `mine`).
        // Requiring it for both dropped every line about one's own moments (found in the N5-C walk).
        val own = mine == true
        val name = authorName?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null && !own) return null
        return MomentBrief(safeId, kind, text?.trim()?.takeIf { it.isNotEmpty() },
            if (kind == MomentKind.WHOS_IN) plan?.let { p ->
                val at = instant(p.at)
                val where = p.where?.trim()?.takeIf { it.isNotEmpty() }
                if (at == null && where == null) null else MomentPlan(at, where)
            } else null,
            calledOff = calledOff == true, mine = own, authorName = if (own) null else name, live = live == true)
    }
}

data class MessageDto(
    val id: String? = null,
    val kind: String? = null,
    val text: String? = null,
    val event: String? = null,
    val by: PersonDto? = null,
    val mine: Boolean? = null,
    val at: String? = null,
    val collapsed: Boolean? = null,
    val notice: NoticeDto? = null,
    val supportSuggested: Boolean? = null
) {
    fun toDomain(): Message? {
        val safeId = uuid(id) ?: return null
        val kind = MessageKind.entries.firstOrNull { it.name == kind } ?: return null
        val words = text?.takeIf { it.isNotBlank() }
        val event = MessageEvent.entries.firstOrNull { it.name == event }
        when (kind) {
            MessageKind.TEXT -> if (words == null) return null
            MessageKind.SYSTEM -> if (event == null) return null
        }
        val own = mine == true
        return Message(safeId, kind, if (kind == MessageKind.TEXT) words else null, if (kind == MessageKind.SYSTEM) event else null,
            by?.toDomain(), own, instant(at),
            collapsed = !own && collapsed == true,
            notice = if (own) notice?.toDomain() else null,
            supportSuggested = own && supportSuggested == true)
    }
}

data class YouDto(val turn: String? = null, val canSend: Boolean? = null, val paused: Boolean? = null)

data class ConversationDto(
    val id: String? = null,
    val kind: String? = null,
    val moment: MomentRefDto? = null,
    val people: List<PersonDto?>? = null,
    val messages: List<MessageDto?>? = null,
    val you: YouDto? = null,
    val closesAt: String? = null,
    val open: Boolean? = null
) {
    fun toDomain(): Conversation? {
        val safeId = uuid(id) ?: return null
        val kind = ConversationKind.entries.firstOrNull { it.name == kind } ?: return null
        val about = moment?.toDomain() ?: return null
        val turn = Turn.entries.firstOrNull { it.name == you?.turn } ?: Turn.CLOSED
        val open = open == true
        // Sending is offered only when everything agrees; the server decides again anyway.
        val canSend = open && you?.canSend == true && turn != Turn.CLOSED && turn != Turn.ENDED && turn != Turn.WAITING
        return Conversation(safeId, kind, about, people.orEmpty().mapNotNull { it?.toDomain() },
            messages.orEmpty().mapNotNull { it?.toDomain() }, You(turn, canSend, you?.paused == true), instant(closesAt), open)
    }
}

data class ReplyItemDto(
    val type: String? = null,
    val moment: MomentRefDto? = null,
    val conversationId: String? = null,
    val people: List<PersonDto?>? = null,
    val morePeople: Boolean? = null,
    val turn: String? = null,
    val open: Boolean? = null,
    val at: String? = null,
    val fresh: Boolean? = null
) {
    fun toDomain(): ReplyItem? {
        val type = ReplyType.entries.firstOrNull { it.name == type } ?: return null
        val about = moment?.toDomain() ?: return null
        val who = people.orEmpty().mapNotNull { it?.toDomain() }.take(3)
        // A line that names someone must have a name to say: the people, or else the author (never one's own blank).
        if (who.isEmpty() && about.authorName == null && type !in NAMELESS) return null
        return ReplyItem(type, about, uuid(conversationId), who,
            morePeople == true, Turn.entries.firstOrNull { it.name == turn }, open == true, instant(at),
            // Same never lights anything (D19).
            fresh = type != ReplyType.SAME && fresh == true)
    }

    private companion object {
        /** Lines whose words name nobody (your own plan, where you stand in someone's plan). */
        val NAMELESS = setOf(ReplyType.PLAN, ReplyType.NOT_IN, ReplyType.CLOSED, ReplyType.CALLED_OFF)
    }
}

data class RepliesDto(
    val needsYou: List<ReplyItemDto?>? = null,
    val plans: List<ReplyItemDto?>? = null,
    val conversations: List<ReplyItemDto?>? = null,
    val same: List<ReplyItemDto?>? = null,
    val fresh: Boolean? = null
) {
    fun toDomain() = RepliesPage(
        needsYou = list(needsYou, NEEDS_YOU),
        plans = list(plans, PLANS),
        conversations = list(conversations, setOf(ReplyType.REPLY)),
        same = list(same, setOf(ReplyType.SAME)),
        fresh = fresh == true
    )

    /** A line in the wrong section is dropped (never shown under a heading that says something else). */
    private fun list(raw: List<ReplyItemDto?>?, allowed: Set<ReplyType>) =
        raw.orEmpty().mapNotNull { it?.toDomain() }.filter { it.type in allowed }

    private companion object {
        val NEEDS_YOU = setOf(ReplyType.ASKS, ReplyType.SAY_MORE, ReplyType.YOUR_TURN, ReplyType.MORE)
        val PLANS = setOf(ReplyType.PLAN, ReplyType.WAITING, ReplyType.NOT_IN, ReplyType.CLOSED, ReplyType.CALLED_OFF)
    }
}

data class MarkerDto(val fresh: Boolean? = null)

/** Body of a private reply and of a message: the words, never in logs. */
data class MessageBodyDto(val text: String) {
    override fun toString() = "MessageBodyDto(<words hidden>)"
}

data class SettingsDto(val privateReplies: String? = null) {
    /** No row or an unknown value = MY_PEOPLE, the default (D17). */
    fun toDomain(): PrivateReplies = PrivateReplies.entries.firstOrNull { it.name == privateReplies } ?: PrivateReplies.MY_PEOPLE
}

private fun instant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
