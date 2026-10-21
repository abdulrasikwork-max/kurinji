package com.safesphere.app.feature.home.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.StatusCardContent
import com.safesphere.app.core.ui.components.StatusTone

/** The words and icon of the Home status card for each [HomeStatus]. */
object HomeStatusCard {
    /** Null = the card is hidden: during an SOS the red banner is the one emergency element. */
    fun contentFor(status: HomeStatus): StatusCardContent? = when (status) {
        HomeStatus.CHECKING -> StatusCardContent(
            R.string.status_checking_label, R.string.status_checking_headline, R.string.status_checking_detail,
            R.drawable.ic_shield_outline, StatusTone.CALM
        )
        HomeStatus.NOTHING_RUNNING -> StatusCardContent(
            R.string.status_idle_label, R.string.status_idle_headline, R.string.status_idle_detail,
            R.drawable.ic_shield, StatusTone.CALM
        )
        HomeStatus.SAFE_WALK -> StatusCardContent(
            R.string.status_walk_label, R.string.status_walk_headline, R.string.status_walk_detail,
            R.drawable.ic_route, StatusTone.CALM
        )
        HomeStatus.CHECK_IN_MISSED -> StatusCardContent(
            R.string.status_missed_label, R.string.status_missed_headline, R.string.status_missed_detail,
            R.drawable.ic_warning, StatusTone.CAUTION
        )
        HomeStatus.EMERGENCY -> null
    }
}

/** A small action tile under the SOS tile; each one opens a real screen (Phase 10A: no placeholders on Home). */
data class QuickAction(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    val destinationId: Int
)

object QuickActions {
    // Phase 10A: one Safe Walk tile (check-ins are part of Safe Walk; a separate "Check-in" tile opened the same screen).
    val all = listOf(
        QuickAction(R.drawable.ic_route, R.string.feature_safe_walk_title, R.id.safeWalkFragment),
        QuickAction(R.drawable.ic_group, R.string.feature_safety_circle_title, R.id.safetyCircleFragment)
    )
}
