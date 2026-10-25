package com.safesphere.app.core.textcheck

/**
 * Phase 13E: the private nudge before sharing a post or a perspective (design §6.2-§6.3). PURE. The draft never
 * leaves the phone for it, nothing is stored, nothing is decided: the person may always share anyway, and the server
 * makes its own check. Words like "Our system thinks this may be…", never "you are…".
 *
 *   - SUPPORT first: the words suggest the person may be going through something hard (a support line, never a block);
 *   - CARE: something the server's policy would collapse or hold (targeted, unquoted, severity 2+; holds at 3);
 *   - CONTACT: a phone number or e-mail address (maybe someone else's: a shop's number is fine);
 *   - NONE: nothing to say (strong language alone is not nudged: banter is fine).
 */
object DraftNudge {

    sealed interface Nudge {
        data object None : Nudge
        data object Support : Nudge
        /** [category] is a server reason code (HARASSMENT, THREAT …); [spans] are code points of the draft. */
        data class Care(val category: String, val spans: List<IntRange>) : Nudge
        data class Contact(val spans: List<IntRange>) : Nudge
    }

    private val COLLAPSE = setOf("HARASSMENT", "SEXUAL_HARASSMENT", "HATE", "THREAT")
    private val HOLD = setOf("THREAT", "SEXUAL_HARASSMENT", "HATE", "DOXXING")
    private val RANK = listOf("THREAT", "SEXUAL_HARASSMENT", "HATE", "DOXXING", "SPAM", "HARASSMENT")

    fun of(result: TextCheck.Result): Nudge {
        if (result.selfHarm) return Nudge.Support
        val live = result.evidence.filter { !it.quoted }
        val acting = live.filter {
            (it.category in HOLD && it.targeted && it.severity >= 3) || (it.category == "SPAM" && it.severity >= 3) ||
                (it.category in COLLAPSE && it.targeted && it.severity >= 2)
        }
        if (acting.isNotEmpty()) {
            val top = acting.minWith(compareBy({ RANK.indexOf(it.category) }, { -it.severity })).category
            return Nudge.Care(top, acting.map { it.start until it.end })
        }
        val contacts = live.filter { it.category == "DOXXING" }
        if (contacts.isNotEmpty()) return Nudge.Contact(contacts.map { it.start until it.end })
        return Nudge.None
    }
}
