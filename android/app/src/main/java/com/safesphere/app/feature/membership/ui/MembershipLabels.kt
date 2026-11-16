package com.safesphere.app.feature.membership.ui

import androidx.annotation.StringRes
import com.safesphere.app.R

/** Phase N3: the person-facing words for the server's refusal and ban codes (an unknown code reads "another reason"). */
object MembershipLabels {

    @StringRes
    fun refusal(reason: String?): Int = when (reason) {
        "NAME_MISMATCH" -> R.string.membership_reason_name_mismatch
        "ROLL_MISMATCH" -> R.string.membership_reason_roll_mismatch
        "NOT_THE_PERSON" -> R.string.membership_reason_not_the_person
        "NOT_MIET_ID" -> R.string.membership_reason_not_miet_id
        "UNREADABLE" -> R.string.membership_reason_unreadable
        "ALREADY_MEMBER" -> R.string.membership_reason_already_member
        "BANNED" -> R.string.membership_reason_banned
        else -> R.string.membership_reason_other
    }

    @StringRes
    fun ban(reason: String?): Int = when (reason) {
        "HARASSMENT" -> R.string.membership_ban_harassment
        "HATE" -> R.string.membership_ban_hate
        "THREATS" -> R.string.membership_ban_threats
        "SEXUAL_CONTENT" -> R.string.membership_ban_sexual_content
        "IMPERSONATION" -> R.string.membership_ban_impersonation
        "FALSE_ID" -> R.string.membership_ban_false_id
        "PRIVACY" -> R.string.membership_ban_privacy
        "REPEATED" -> R.string.membership_ban_repeated
        else -> R.string.membership_reason_other
    }
}
