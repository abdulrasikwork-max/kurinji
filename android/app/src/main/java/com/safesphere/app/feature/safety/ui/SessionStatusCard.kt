package com.safesphere.app.feature.safety.ui

import com.safesphere.app.R
import com.safesphere.app.core.ui.components.StatusCardContent
import com.safesphere.app.core.ui.components.StatusTone

/** The words and icon of the Safety hub's status card for each [SessionStatus] (moved from Home in Phase 13F). */
object SessionStatusCard {
    /** Null = the card is hidden: during an SOS the red banner is the one emergency element. */
    fun contentFor(status: SessionStatus): StatusCardContent? = when (status) {
        SessionStatus.CHECKING -> StatusCardContent(
            R.string.status_checking_label, R.string.status_checking_headline, R.string.status_checking_detail,
            R.drawable.ic_shield_outline, StatusTone.CALM
        )
        SessionStatus.NOTHING_RUNNING -> StatusCardContent(
            R.string.status_idle_label, R.string.status_idle_headline, R.string.status_idle_detail,
            R.drawable.ic_shield, StatusTone.CALM
        )
        SessionStatus.SAFE_WALK -> StatusCardContent(
            R.string.status_walk_label, R.string.status_walk_headline, R.string.status_walk_detail,
            R.drawable.ic_route, StatusTone.CALM
        )
        SessionStatus.CHECK_IN_MISSED -> StatusCardContent(
            R.string.status_missed_label, R.string.status_missed_headline, R.string.status_missed_detail,
            R.drawable.ic_warning, StatusTone.CAUTION
        )
        SessionStatus.EMERGENCY -> null
    }
}
