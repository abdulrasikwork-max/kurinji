package com.safesphere.app.feature.replies.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.ui.ResponseWords
import com.safesphere.app.feature.replies.domain.Conversation
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.MessageEvent
import com.safesphere.app.feature.replies.domain.RepliesPage
import com.safesphere.app.feature.replies.domain.ReplyItem
import com.safesphere.app.feature.replies.domain.ReplyType
import com.safesphere.app.feature.replies.domain.Turn

/** One row of Replies. */
sealed interface RepliesRow {
    data class Section(@StringRes val title: Int) : RepliesRow
    data class Line(val item: ReplyItem) : RepliesRow
    data object Loading : RepliesRow
    data object Empty : RepliesRow
    /** Replies ends, and says so (D19: then it ends). */
    data object End : RepliesRow
}

/**
 * Phase N5: the words of Replies, plans and private replies. Pure (resource ids only), so the rules are unit tested:
 * names, never a number; a turn is said as whose turn it is, never as "seen" or "read"; nobody is ever "declined".
 */
object RepliesLabels {

    /** The four sections in their order, empty ones left out, then exactly one closing row. */
    fun rows(page: RepliesPage?, loading: Boolean): List<RepliesRow> {
        if (page == null) return if (loading) listOf(RepliesRow.Loading) else emptyList()
        if (page.isEmpty) return listOf(RepliesRow.Empty)
        val rows = ArrayList<RepliesRow>()
        fun section(@StringRes title: Int, items: List<ReplyItem>) {
            if (items.isEmpty()) return
            rows += RepliesRow.Section(title)
            items.forEach { rows += RepliesRow.Line(it) }
        }
        section(R.string.replies_section_needs_you, page.needsYou)
        section(R.string.replies_section_plans, page.plans)
        section(R.string.replies_section_conversations, page.conversations)
        section(R.string.replies_section_same, page.same)
        rows += RepliesRow.End
        return rows
    }

    /** The line's first words: who, and what they did or where the reader stands. */
    fun title(item: ReplyItem): ResponseWords {
        val names = item.people.map { it.name }
        fun named(@StringRes one: Int, @StringRes many: Int) = if (names.isEmpty()) ResponseWords(many, listOf(item.moment.authorName.orEmpty()))
            else ResponseWords(if (names.size == 1 && !item.morePeople) one else many, names, item.morePeople)
        return when (item.type) {
            ReplyType.ASKS -> named(R.string.response_author_asking_one, R.string.response_author_asking_many)
            ReplyType.SAY_MORE -> named(R.string.response_author_say_more_one, R.string.response_author_say_more_many)
            ReplyType.SAME -> named(R.string.response_author_same_one, R.string.response_author_same_many)
            ReplyType.YOUR_TURN -> ResponseWords(R.string.replies_line_your_turn, arg = names.firstOrNull() ?: item.moment.authorName.orEmpty())
            ReplyType.MORE -> ResponseWords(R.string.replies_line_more, arg = item.moment.authorName.orEmpty())
            ReplyType.PLAN -> if (item.moment.mine) ResponseWords(R.string.replies_line_plan_mine)
                else ResponseWords(R.string.replies_line_plan, arg = item.moment.authorName.orEmpty())
            ReplyType.WAITING -> ResponseWords(R.string.response_plan_waiting, arg = item.moment.authorName.orEmpty())
            ReplyType.NOT_IN -> ResponseWords(R.string.response_plan_removed)
            ReplyType.CLOSED -> ResponseWords(R.string.response_plan_closed)
            ReplyType.CALLED_OFF -> ResponseWords(R.string.response_plan_called_off)
            ReplyType.REPLY -> ResponseWords(R.string.replies_line_reply, arg = names.firstOrNull() ?: item.moment.authorName.orEmpty())
        }
    }

    /** Where a conversation stands, in words (null = nothing to say). Never "seen" or "read". */
    @StringRes
    fun turn(turn: Turn?): Int? = when (turn) {
        Turn.YOUR_TURN -> R.string.replies_turn_yours
        Turn.WAITING -> R.string.replies_turn_waiting
        Turn.CLOSED -> R.string.replies_turn_closed
        Turn.ENDED -> R.string.replies_turn_ended
        Turn.OPEN, null -> null
    }

    /** What a line opens: the conversation when it has one, otherwise the moment. */
    fun opensConversation(item: ReplyItem): Boolean = item.conversationId != null

    /** A plan's or a reply's quiet line, as the reader reads it (the person is named, or "You"). */
    @StringRes
    fun event(event: MessageEvent, mine: Boolean): Int = when (event) {
        MessageEvent.IN -> if (mine) R.string.conversation_event_in_you else R.string.conversation_event_in
        MessageEvent.LEFT -> if (mine) R.string.conversation_event_left_you else R.string.conversation_event_left
        MessageEvent.CALLED_OFF -> R.string.conversation_event_called_off
        MessageEvent.HAPPENED -> if (mine) R.string.conversation_event_happened_you else R.string.conversation_event_happened
        MessageEvent.ENDED -> if (mine) R.string.conversation_event_ended_you else R.string.conversation_event_ended
    }

    /** What stands where the writing box would be, when the reader cannot write now. */
    @StringRes
    fun cannotSend(conversation: Conversation): Int = when {
        conversation.you.turn == Turn.ENDED -> R.string.conversation_ended
        !conversation.open || conversation.you.turn == Turn.CLOSED -> R.string.conversation_closed
        conversation.you.paused -> R.string.conversation_paused
        conversation.you.turn == Turn.WAITING -> R.string.conversation_waiting
        else -> R.string.conversation_closed
    }

    /** "Plan · Canteen at four?" or "Private reply · …": what the conversation is about. */
    @StringRes
    fun heading(conversation: Conversation): Int =
        if (conversation.kind == ConversationKind.PLAN) R.string.conversation_heading_plan
        else if (conversation.moment.mine) R.string.conversation_heading_reply_mine
        else R.string.conversation_heading_reply

    /** Only a private reply can be ended (by either person); a plan closes on its own or is called off. */
    fun canEnd(conversation: Conversation): Boolean =
        conversation.kind == ConversationKind.REPLY && conversation.open && conversation.you.turn != Turn.ENDED &&
            conversation.you.turn != Turn.CLOSED

    /**
     * N-92: "This happened" is offered in a plan that is open, not called off, after its time (or without one), once per
     * plan (a HAPPENED line means someone already shared it). The server decides again (a My people plan: only its
     * author; it answers with its own words).
     */
    /**
     * N6 (D24, N-108): "Let someone know · On my way" in a plan the reader belongs to, while it is open and not called
     * off. The note carries only the plan's "where" and time; never its words, never anyone's name.
     */
    fun canOfferGoing(conversation: Conversation): Boolean =
        conversation.kind == ConversationKind.PLAN && conversation.open && !conversation.moment.calledOff

    fun canShareHappened(conversation: Conversation, now: java.time.Instant): Boolean =
        conversation.kind == ConversationKind.PLAN && conversation.open && !conversation.moment.calledOff &&
            conversation.moment.plan?.at?.let { !it.isAfter(now) } != false &&
            conversation.messages.none { it.event == MessageEvent.HAPPENED }

    /** A moment named in a line: its words, or that it is a photo or a plan. */
    @StringRes
    fun momentFallback(kind: MomentKind): Int = if (kind == MomentKind.WHOS_IN) R.string.moment_kind_whos_in else R.string.moment_photo
}
