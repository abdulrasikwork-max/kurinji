package com.safesphere.app.core.push

/**
 * N7 (D34): where a tap on a notification goes. Travels through the non-exported router activity as two extras (a
 * name and an id) that are validated again on the way in, so nothing but these screens can be opened.
 */
sealed class PushTarget(val name: String, val id: String?) {
    class Moment(id: String) : PushTarget(MOMENT, id)
    class Conversation(id: String) : PushTarget(CONVERSATION, id)
    data object Replies : PushTarget(REPLIES, null)
    data object Requests : PushTarget(REQUESTS, null)
    data object Account : PushTarget(ACCOUNT, null)
    data object Membership : PushTarget(MEMBERSHIP, null)
    data object You : PushTarget(YOU, null)
    data object Staff : PushTarget(STAFF, null)
    data object CircleRequests : PushTarget(CIRCLE_REQUESTS, null)
    class CircleAlert(emergencyId: String) : PushTarget(CIRCLE_ALERT, emergencyId)
    data object OnMyWay : PushTarget(ON_MY_WAY, null)

    companion object {
        const val EXTRA_TARGET = "irl.push.target"
        const val EXTRA_ID = "irl.push.id"

        private const val MOMENT = "moment"
        private const val CONVERSATION = "conversation"
        private const val REPLIES = "replies"
        private const val REQUESTS = "requests"
        private const val ACCOUNT = "account"
        private const val MEMBERSHIP = "membership"
        private const val YOU = "you"
        private const val STAFF = "staff"
        private const val CIRCLE_REQUESTS = "circle_requests"
        private const val CIRCLE_ALERT = "circle_alert"
        private const val ON_MY_WAY = "on_my_way"

        private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val NUMBER = Regex("^[1-9][0-9]{0,18}$")

        /** Null for anything that is not one of the targets with a well-formed id. */
        fun from(name: String?, id: String?): PushTarget? = when (name) {
            MOMENT -> id?.takeIf { UUID.matches(it) }?.let(::Moment)
            CONVERSATION -> id?.takeIf { UUID.matches(it) }?.let(::Conversation)
            CIRCLE_ALERT -> id?.takeIf { NUMBER.matches(it) }?.let(::CircleAlert)
            REPLIES -> Replies
            REQUESTS -> Requests
            ACCOUNT -> Account
            MEMBERSHIP -> Membership
            YOU -> You
            STAFF -> Staff
            CIRCLE_REQUESTS -> CircleRequests
            ON_MY_WAY -> OnMyWay
            else -> null
        }
    }
}

/** A button on a notification (Circle SOS: Call, Call 112). Opens the phone's dialer; never calls by itself. */
data class PushAction(val label: String, val dialNumber: String)

/**
 * What a renderer decided to show (D32: names and structure, never what anyone wrote unless the person turned on "Show
 * what people wrote"). [thread] makes the notification id: a newer push about the same thing replaces the older one.
 * [publicTitle] is what the lock screen shows (neutral, no names).
 */
data class PushNotice(
    val title: String,
    val text: String?,
    val thread: String,
    val target: PushTarget,
    val publicTitle: String,
    val actions: List<PushAction> = emptyList()
)

/**
 * What a renderer answers: [Show], [Nothing] (the thing is gone, held, blocked or no longer concerns this person: show
 * nothing, D26) or [Generic] (IRL could not be reached: a neutral line for the kind's channel).
 */
sealed interface Rendered {
    data class Show(val notice: PushNotice) : Rendered
    data object Nothing : Rendered
    data object Generic : Rendered
}

/** Registered per kind by the feature that owns it (core/push imports no feature). */
fun interface PushRenderer {
    suspend fun render(payload: PushPayload): Rendered
}

/**
 * How a renderer reads IRL's answer: success → [show]; the thing is gone, not allowed or nobody is signed in → nothing
 * (D26: something deleted, held or blocked is never shown); anything else (no connection, server trouble) → the generic
 * line.
 */
inline fun <T> com.safesphere.app.core.network.ApiResult<T>.renderOr(show: (T) -> Rendered): Rendered = when (this) {
    is com.safesphere.app.core.network.ApiResult.Success -> show(data)
    is com.safesphere.app.core.network.ApiResult.Failure -> when (error) {
        com.safesphere.app.core.network.ApiError.NOT_FOUND,
        com.safesphere.app.core.network.ApiError.FORBIDDEN,
        com.safesphere.app.core.network.ApiError.UNAUTHORIZED -> Rendered.Nothing
        else -> Rendered.Generic
    }
}
