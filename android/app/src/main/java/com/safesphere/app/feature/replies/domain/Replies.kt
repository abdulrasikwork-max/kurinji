package com.safesphere.app.feature.replies.domain

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.moments.domain.MomentNotice
import com.safesphere.app.feature.moments.domain.MomentPlan
import com.safesphere.app.feature.moments.domain.PlanPerson
import java.time.Instant

/*
 * Phase N5 (D17 to D19): plans, private replies and Replies, as the app sees them. Nothing here says whether or when
 * anyone read anything, who is typing, or how many: no read state, no "seen", no count. Exact times exist only inside
 * a conversation, for its members. Nothing is stored on the phone.
 */

enum class ConversationKind { PLAN, REPLY }

/**
 * Where the reader is in a conversation. A private reply takes turns (YOUR_TURN, WAITING); a plan is OPEN; CLOSED =
 * read-only after its time; ENDED = one of the two ended a private reply.
 */
enum class Turn { YOUR_TURN, WAITING, OPEN, CLOSED, ENDED }

enum class MessageKind { TEXT, SYSTEM }

/** The quiet lines of a plan or a reply: someone is in, can't make it, it was called off, it happened, it was ended. */
enum class MessageEvent { IN, LEFT, CALLED_OFF, HAPPENED, ENDED }

/** The moment a conversation or a Replies line is about, briefly. [live] = still on Today (Replies only). */
data class MomentBrief(
    val id: String,
    val kind: MomentKind,
    val text: String?,
    val plan: MomentPlan?,
    val calledOff: Boolean,
    val mine: Boolean,
    /** Someone else's moment: always set. The reader's own: null (the server never sends one's own name back). */
    val authorName: String?,
    val live: Boolean
)

/**
 * One message. [by] = who wrote it (TEXT) or who it is about (SYSTEM); [at] the exact time (members only);
 * [collapsed] = behind a tap for the reader; [notice] = what the WRITER is told about their own message, never anyone
 * else; [supportSuggested] only in the writer's own answer to sending.
 */
data class Message(
    val id: String,
    val kind: MessageKind,
    val text: String?,
    val event: MessageEvent?,
    val by: PlanPerson?,
    val mine: Boolean,
    val at: Instant?,
    val collapsed: Boolean,
    val notice: MomentNotice?,
    val supportSuggested: Boolean
)

/** What the reader may do: [canSend] now; [paused] = a responses pause stops sending (reading and leaving never). */
data class You(val turn: Turn, val canSend: Boolean, val paused: Boolean)

/** A plan (the author and the people in it) or a private reply (the other person in [people]). Oldest message first. */
data class Conversation(
    val id: String,
    val kind: ConversationKind,
    val moment: MomentBrief,
    val people: List<PlanPerson>,
    val messages: List<Message>,
    val you: You,
    val closesAt: Instant?,
    val open: Boolean
)

/**
 * The kinds of Replies lines (D19). Needs you: ASKS, SAY_MORE, YOUR_TURN, MORE. Plans: PLAN, WAITING, NOT_IN, CLOSED,
 * CALLED_OFF. Private replies: REPLY. Feel the same: SAME (never lights the marker).
 */
enum class ReplyType { ASKS, SAY_MORE, YOUR_TURN, MORE, PLAN, WAITING, NOT_IN, CLOSED, CALLED_OFF, REPLY, SAME }

/** One line of Replies: names (at most 3, [morePeople] = and others), never a number. */
data class ReplyItem(
    val type: ReplyType,
    val moment: MomentBrief,
    val conversationId: String?,
    val people: List<PlanPerson>,
    val morePeople: Boolean,
    val turn: Turn?,
    val open: Boolean,
    val at: Instant?,
    val fresh: Boolean
)

data class RepliesPage(
    val needsYou: List<ReplyItem>,
    val plans: List<ReplyItem>,
    val conversations: List<ReplyItem>,
    val same: List<ReplyItem>,
    val fresh: Boolean
) {
    val isEmpty: Boolean get() = needsYou.isEmpty() && plans.isEmpty() && conversations.isEmpty() && same.isEmpty()
}

/** D17: who may reply privately to the person's moments (no setting = MY_PEOPLE). */
enum class PrivateReplies { MY_PEOPLE, COMMUNITY, NOBODY }

/**
 * Phase N5 on the backend: Replies, plans, private replies, the setting. A refused answer comes with the server's own
 * words (409 turns and closing, 403 the setting or a pause).
 */
interface RepliesRepository {
    suspend fun replies(): ApiResult<RepliesPage>
    /** D19: true = something arrived since the person last opened Replies. */
    suspend fun marker(): ApiResult<Boolean>
    /** D19: the person opened Replies (their own time, never per message). */
    suspend fun opened(): ApiResult<Unit>
    suspend fun conversation(id: String): ApiResult<Conversation>
    suspend fun send(conversationId: String, text: String): ApiResult<Message>
    suspend fun end(conversationId: String): ApiResult<Unit>
    suspend fun startReply(momentId: String, text: String): ApiResult<Conversation>
    suspend fun privateReplies(): ApiResult<PrivateReplies>
    suspend fun setPrivateReplies(value: PrivateReplies): ApiResult<PrivateReplies>
}
