package com.safesphere.app.core.push

import java.time.LocalTime

/** N7: the small decisions of the phone side, pure and unit-tested. */
object PushRules {

    /** D30: 11 pm to 7 am phone time; social notifications then come without sound (they still come). */
    val QUIET_FROM: LocalTime = LocalTime.of(23, 0)
    val QUIET_UNTIL: LocalTime = LocalTime.of(7, 0)

    /** D26: how long a renderer may read IRL before the generic line is shown instead. */
    const val RENDER_TIMEOUT_MS = 8_000L

    /** D30: social notifications go away by themselves after a day, like the moments they are about. */
    const val SOCIAL_TIMEOUT_MS = 24L * 60 * 60 * 1000

    /** D33: "Not now" on the notification card waits this long before IRL asks again. */
    const val NOT_NOW_DAYS = 30L

    fun silentNow(kind: PushKind, quietAtNight: Boolean, now: LocalTime): Boolean =
        kind.social && quietAtNight && (!now.isBefore(QUIET_FROM) || now.isBefore(QUIET_UNTIL))

    /** D34: while IRL is on the screen, social pushes only refresh the dot and the visible screen. */
    fun showInTray(kind: PushKind, appVisible: Boolean): Boolean = !kind.social || !appVisible

    /** A stable notification id per thread (one per plan, conversation, request, SOS), never 0. */
    fun notificationId(thread: String): Int {
        val h = thread.hashCode()
        return if (h == 0 || h == Int.MIN_VALUE) 1 else kotlin.math.abs(h)
    }

    /**
     * D28: what the phone should do about its token. Register when push is set up, someone is signed in (a session id)
     * and notifications are allowed, and this sign-in has not registered this token yet; remove when nobody is signed in
     * and a token was registered before; otherwise nothing.
     */
    fun registration(configured: Boolean, sessionId: String?, allowed: Boolean, token: String?,
                     registered: Pair<String, String>?): Registration = when {
        !configured -> Registration.NOTHING
        sessionId == null -> if (registered != null) Registration.FORGET else Registration.NOTHING
        !allowed || token == null -> Registration.NOTHING
        registered == sessionId to token -> Registration.NOTHING
        else -> Registration.REGISTER
    }

    enum class Registration { REGISTER, FORGET, NOTHING }

    /** The "sid" claim of the sign-in token (this phone's sign-in), or null when it cannot be read. */
    fun sessionIdOf(token: String?): String? = try {
        val payload = token?.split('.')?.getOrNull(1)
        if (payload == null) null else {
            val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            SID.find(json)?.groupValues?.get(1)
        }
    } catch (e: IllegalArgumentException) {
        null
    }

    private val SID = Regex("\"sid\"\\s*:\\s*\"([A-Za-z0-9_-]{1,64})\"")
}
