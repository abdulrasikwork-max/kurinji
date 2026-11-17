package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.replies.domain.Conversation
import com.safesphere.app.feature.replies.domain.ConversationKind
import com.safesphere.app.feature.replies.domain.Message
import com.safesphere.app.feature.replies.domain.MessageKind
import com.safesphere.app.feature.replies.domain.MomentBrief
import com.safesphere.app.feature.replies.domain.PrivateReplies
import com.safesphere.app.feature.replies.domain.RepliesPage
import com.safesphere.app.feature.replies.domain.RepliesRepository
import com.safesphere.app.feature.replies.domain.Turn
import com.safesphere.app.feature.replies.domain.You

fun brief(id: String = "m1", mine: Boolean = false, kind: MomentKind = MomentKind.SAY) =
    MomentBrief(id, kind, "Canteen at four?", null, calledOff = false, mine = mine, authorName = if (mine) null else "Asha", live = true) // as the server sends it

fun conversation(
    id: String = "c1",
    kind: ConversationKind = ConversationKind.REPLY,
    turn: Turn = Turn.YOUR_TURN,
    canSend: Boolean = true,
    open: Boolean = true,
    messages: List<Message> = emptyList()
) = Conversation(id, kind, brief(), emptyList(), messages, You(turn, canSend, paused = false), null, open)

fun message(id: String, mine: Boolean = false, text: String = "hello", support: Boolean = false) =
    Message(id, MessageKind.TEXT, text, null, null, mine, null, collapsed = false, notice = null, supportSuggested = support)

/** N5 (A2): Replies in memory; every call is recorded. */
class FakeRepliesRepository : RepliesRepository {

    val calls = ArrayList<String>()
    var page: ApiResult<RepliesPage> = ApiResult.Success(RepliesPage(emptyList(), emptyList(), emptyList(), emptyList(), false))
    val markers = ArrayDeque<ApiResult<Boolean>>()
    var openedResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var conversationResult: ApiResult<Conversation> = ApiResult.Failure(ApiError.NOT_FOUND)
    var sendResult: ApiResult<Message> = ApiResult.Success(message("sent", mine = true))
    var startResult: ApiResult<Conversation> = ApiResult.Success(conversation("new", turn = Turn.WAITING, canSend = false))
    var endResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var setting: ApiResult<PrivateReplies> = ApiResult.Success(PrivateReplies.MY_PEOPLE)
    /** When set, the marker call waits for it (to test one call at a time). */
    var markerGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun replies(): ApiResult<RepliesPage> {
        calls += "replies"
        return page
    }

    override suspend fun marker(): ApiResult<Boolean> {
        calls += "marker"
        markerGate?.await()
        return markers.removeFirstOrNull() ?: ApiResult.Success(false)
    }

    override suspend fun opened(): ApiResult<Unit> {
        calls += "opened"
        return openedResult
    }

    override suspend fun conversation(id: String): ApiResult<Conversation> {
        calls += "conversation:$id"
        return conversationResult
    }

    override suspend fun send(conversationId: String, text: String): ApiResult<Message> {
        calls += "send:$conversationId:$text"
        return sendResult
    }

    override suspend fun end(conversationId: String): ApiResult<Unit> {
        calls += "end:$conversationId"
        return endResult
    }

    override suspend fun startReply(momentId: String, text: String): ApiResult<Conversation> {
        calls += "start:$momentId:$text"
        return startResult
    }

    override suspend fun privateReplies(): ApiResult<PrivateReplies> {
        calls += "settings"
        return setting
    }

    override suspend fun setPrivateReplies(value: PrivateReplies): ApiResult<PrivateReplies> {
        calls += "setSettings:$value"
        return (setting as? ApiResult.Failure) ?: ApiResult.Success(value)
    }
}
