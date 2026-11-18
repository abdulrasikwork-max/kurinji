package com.safesphere.app.feature.replies.push

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.core.push.PushKind
import com.safesphere.app.core.push.PushNotice
import com.safesphere.app.core.push.PushRenderer
import com.safesphere.app.core.push.PushTarget
import com.safesphere.app.core.push.Rendered
import com.safesphere.app.core.push.renderOr
import com.safesphere.app.feature.moments.domain.MomentPlan
import com.safesphere.app.feature.moments.domain.MomentRepository
import com.safesphere.app.feature.moments.domain.PlanPerson
import com.safesphere.app.feature.moments.domain.ResponseRepository
import com.safesphere.app.feature.moments.ui.MomentCardBinder
import com.safesphere.app.feature.replies.domain.Conversation
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.MessageKind
import com.safesphere.app.feature.replies.domain.RepliesRepository

/**
 * N7 (D29, D32): what a plan, a private reply or "said more" push says. Each reads IRL through the normal,
 * visibility-checked routes (something deleted, held or blocked answers 404 and shows nothing). Names and structure
 * only; the words someone wrote appear only when the person turned on "Show what people wrote" (never on the lock
 * screen). Reading tells nobody anything (no read state).
 */
class RepliesPushRenderers(
    private val context: Context,
    private val moments: MomentRepository,
    private val responses: ResponseRepository,
    private val replies: RepliesRepository,
    private val showContent: suspend () -> Boolean
) {

    fun all(): Map<PushKind, PushRenderer> = mapOf(
        PushKind.PLAN_ASK to PushRenderer { p -> planAsk(p.subject) },
        PushKind.PLAN_ADDED to PushRenderer { p -> planAdded(p.subject) },
        PushKind.PLAN_MESSAGE to PushRenderer { p -> planMessage(p.subject) },
        PushKind.PLAN_CALLED_OFF to PushRenderer { p -> calledOff(p.subject) },
        PushKind.REPLY_TURN to PushRenderer { p -> replyTurn(p.subject) },
        PushKind.MORE_ADDED to PushRenderer { p -> moreAdded(p.subject) }
    )

    private val publicLine get() = context.getString(R.string.push_public_replies)

    /** Someone said I'm in on the reader's own plan (batched: up to two names, then "and others"). */
    private suspend fun planAsk(momentId: String): Rendered = responses.people(momentId).renderOr { people ->
        val names = people.asking.map(PlanPerson::name).asReversed()
        if (names.isEmpty()) return@renderOr Rendered.Nothing // already added or set aside: nothing new to say
        val title = when (names.size) {
            1 -> context.getString(R.string.push_plan_ask_one, names[0])
            2 -> context.getString(R.string.push_plan_ask_two, names[0], names[1])
            else -> context.getString(R.string.push_plan_ask_more, names[0], names[1])
        }
        val time = when (val m = moments.moment(momentId)) {
            is com.safesphere.app.core.network.ApiResult.Success -> m.data.plan.timeText()
            else -> null
        }
        val text = time?.let { context.getString(R.string.push_plan_ask_text, it) }
            ?: context.getString(R.string.push_plan_ask_text_no_time)
        Rendered.Show(PushNotice(title, text, "plan-$momentId", PushTarget.Moment(momentId), publicLine))
    }

    private suspend fun planAdded(conversationId: String): Rendered = replies.conversation(conversationId).renderOr { c ->
        val author = c.moment.authorName ?: return@renderOr Rendered.Nothing
        val time = c.moment.plan.timeText()
        val text = if (time != null) context.getString(R.string.push_plan_added_text, author, time)
        else context.getString(R.string.push_plan_added_text_no_time, author)
        Rendered.Show(PushNotice(context.getString(R.string.push_plan_added_title), text, "plan-${c.moment.id}",
            PushTarget.Conversation(conversationId), publicLine))
    }

    private suspend fun planMessage(conversationId: String): Rendered = replies.conversation(conversationId).renderOr { c ->
        if (c.kind != ConversationKind.PLAN) return@renderOr Rendered.Nothing
        val title = c.moment.authorName?.let { context.getString(R.string.push_plan_message_title, it) }
            ?: context.getString(R.string.push_plan_message_own)
        Rendered.Show(PushNotice(title, words(c), "plan-${c.moment.id}", PushTarget.Conversation(conversationId),
            publicLine))
    }

    private suspend fun calledOff(momentId: String): Rendered = moments.moment(momentId).renderOr { m ->
        val time = m.plan.timeText()
        val text = if (time != null) context.getString(R.string.push_plan_called_off_text, m.author.name, time)
        else context.getString(R.string.push_plan_called_off_text_no_time, m.author.name)
        Rendered.Show(PushNotice(context.getString(R.string.push_plan_called_off_title), text, "plan-$momentId",
            PushTarget.Moment(momentId), publicLine))
    }

    private suspend fun replyTurn(conversationId: String): Rendered = replies.conversation(conversationId).renderOr { c ->
        if (c.kind != ConversationKind.REPLY) return@renderOr Rendered.Nothing
        val other = c.people.firstOrNull()?.name ?: return@renderOr Rendered.Nothing
        val texts = c.messages.count { it.kind == MessageKind.TEXT }
        val title = if (texts <= 1) context.getString(R.string.push_reply_first, other)
        else context.getString(R.string.push_reply_turn, other)
        Rendered.Show(PushNotice(title, words(c), "conversation-$conversationId",
            PushTarget.Conversation(conversationId), publicLine))
    }

    private suspend fun moreAdded(momentId: String): Rendered = moments.moment(momentId).renderOr { m ->
        Rendered.Show(PushNotice(context.getString(R.string.push_more_added, m.author.name), null, "more-$momentId",
            PushTarget.Moment(momentId), publicLine))
    }

    /** The newest words someone else wrote, only with "Show what people wrote" on; never collapsed words. */
    private suspend fun words(c: Conversation): String? {
        if (!showContent()) return null
        return c.messages.lastOrNull { it.kind == MessageKind.TEXT && !it.mine && !it.collapsed }?.text
    }

    private fun MomentPlan?.timeText(): String? = this?.at?.let { MomentCardBinder.planTime(context, it) }
}
