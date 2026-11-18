package com.safesphere.app.core.push

/**
 * N7 (D26, D29): the 14 kinds the server can send (the same names as the server's PushType; a new kind needs a
 * decision). [social] kinds are hidden while IRL is on the screen (D34) and follow "quiet at night" (D30); the others
 * always show.
 */
enum class PushKind(val channel: PushChannel, val social: Boolean) {
    PLAN_ASK(PushChannel.PLANS, true),
    PLAN_ADDED(PushChannel.PLANS, true),
    PLAN_MESSAGE(PushChannel.PLANS, true),
    PLAN_CALLED_OFF(PushChannel.PLANS, true),
    REPLY_TURN(PushChannel.REPLIES, true),
    MORE_ADDED(PushChannel.REPLIES, true),
    PEOPLE_REQUEST(PushChannel.PEOPLE, true),
    ACCOUNT_SIGN_IN(PushChannel.ACCOUNT, false),
    MEMBERSHIP_DECIDED(PushChannel.ACCOUNT, false),
    CONTENT_DECIDED(PushChannel.ACCOUNT, false),
    CIRCLE_REQUEST(PushChannel.PEOPLE, false),
    CIRCLE_SOS(PushChannel.CIRCLE_SOS, false),
    OMW_REMINDER(PushChannel.ON_MY_WAY, false),
    STAFF_WAITING(PushChannel.STAFF, false)
}

/** The channels of §5.1 that pushes use (Emergency location stays the SOS service's own). */
enum class PushChannel(val id: String) {
    REPLIES("irl_replies"),
    PLANS("irl_plans"),
    PEOPLE("irl_people"),
    ACCOUNT("irl_account"),
    CIRCLE_SOS("irl_circle_sos"),
    ON_MY_WAY("safety_alerts"),
    STAFF("irl_staff")
}

/**
 * Everything a push carries (D26): a kind, one opaque subject id, the event id (the same on every phone of one event)
 * and the payload version. Nothing else is read from it, ever.
 */
data class PushPayload(val kind: PushKind, val subject: String, val eventId: String) {

    companion object {
        private val SUBJECT = Regex("^[A-Za-z0-9-]{1,64}$")
        private val EVENT = Regex("^[0-9a-f-]{36}$")

        /** Null for anything that is not exactly an IRL pointer of version 1 (unknown kind, bad id, extra junk). */
        fun parse(data: Map<String, String>): PushPayload? {
            if (data["v"] != "1") return null
            val kind = PushKind.entries.firstOrNull { it.name == data["t"] } ?: return null
            val subject = data["s"]?.takeIf { SUBJECT.matches(it) } ?: return null
            val event = data["m"]?.takeIf { EVENT.matches(it) } ?: return null
            return PushPayload(kind, subject, event)
        }
    }
}
