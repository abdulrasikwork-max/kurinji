package com.safesphere.app.feature.social.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.social.domain.DiscoverReason

/**
 * Phase 13C: the words for "Why am I seeing this?". The server sends codes (C8); the phone writes the sentences, and
 * none of them is about how many people did anything.
 */
object DiscoverTexts {

    @StringRes
    fun of(reason: DiscoverReason): Int = when (reason) {
        DiscoverReason.IN_THIS_CITY -> R.string.discover_reason_in_this_city
        DiscoverReason.RECENT -> R.string.discover_reason_recent
        DiscoverReason.HAS_PLACE -> R.string.discover_reason_has_place
        DiscoverReason.HAS_WHEN -> R.string.discover_reason_has_when
        DiscoverReason.HAS_TIP -> R.string.discover_reason_has_tip
        DiscoverReason.MATCHES_YOUR_INTERESTS -> R.string.discover_reason_matches_interests
        DiscoverReason.BEEN_HERE_TOO -> R.string.discover_reason_been_here_too
        DiscoverReason.OPEN_ASK -> R.string.discover_reason_open_ask
        DiscoverReason.THEME_OF_THE_WEEK -> R.string.discover_reason_theme
        DiscoverReason.FROM_A_CONNECTION -> R.string.discover_reason_from_connection
        DiscoverReason.NEW_PERSPECTIVES -> R.string.discover_reason_new_comments
    }
}
