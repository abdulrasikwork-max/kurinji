package com.safesphere.app.core.push

import android.content.Context
import com.safesphere.app.R

/**
 * N7 (D29): the account-side kinds that need no reading beyond what the push says: a new sign-in, a decision on your own
 * moment, message or appeal (no reason on screen: the item itself says it), and the staff summary. The membership
 * decision is rendered by `feature/membership` (it reads the person's own status).
 */
class AccountPushRenderers(private val context: Context) {

    fun all(): Map<PushKind, PushRenderer> = mapOf(
        PushKind.ACCOUNT_SIGN_IN to PushRenderer { _ ->
            Rendered.Show(PushNotice(context.getString(R.string.push_sign_in_title),
                context.getString(R.string.push_sign_in_text), "account-sign-in", PushTarget.Account,
                context.getString(R.string.push_public_account)))
        },
        PushKind.CONTENT_DECIDED to PushRenderer { p -> Rendered.Show(decided(p.subject)) },
        PushKind.STAFF_WAITING to PushRenderer { _ ->
            Rendered.Show(PushNotice(context.getString(R.string.push_staff_waiting), null, "staff-waiting",
                PushTarget.Staff, context.getString(R.string.push_staff_waiting)))
        }
    )

    /** Subjects: "m-<moment>", "c-<conversation>" or "decisions" (an appeal's outcome). */
    private fun decided(subject: String): PushNotice {
        val public = context.getString(R.string.push_public_account)
        val id = subject.drop(2)
        return when {
            subject.startsWith("m-") && PushTarget.from("moment", id) != null ->
                PushNotice(context.getString(R.string.push_content_moment), null, "decided-$subject",
                    PushTarget.Moment(id), public)
            subject.startsWith("c-") && PushTarget.from("conversation", id) != null ->
                PushNotice(context.getString(R.string.push_content_message), null, "decided-$subject",
                    PushTarget.Conversation(id), public)
            else -> PushNotice(context.getString(R.string.push_content_appeal), null, "decided-appeal",
                PushTarget.You, public)
        }
    }
}
