package com.safesphere.app.core.ui.components

import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.google.android.material.R as MaterialR

/**
 * Every status label in SafeSphere. Each one has TEXT and (mostly) an ICON as well as a colour,
 * so meaning never depends on colour alone.
 *
 * AVAILABLE   = works today.
 * PREVIEW     = shows how a feature will look; it does nothing yet.
 * COMING_SOON = not built yet.
 * SAMPLE      = placeholder content shown only to demonstrate the layout.
 * SAFE / CAUTION / CONCERN = the three safety levels used in the community preview.
 */
enum class BadgeStatus(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int?,
    @AttrRes val containerAttr: Int,
    @AttrRes val contentAttr: Int
) {
    AVAILABLE(R.string.badge_available, R.drawable.ic_check, R.attr.colorSafeContainer, R.attr.colorOnSafeContainer),
    PREVIEW(R.string.badge_preview, R.drawable.ic_visibility, MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer),
    COMING_SOON(R.string.coming_soon, R.drawable.ic_schedule, MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer),
    SAMPLE(R.string.badge_sample, null, MaterialR.attr.colorSecondaryContainer, MaterialR.attr.colorOnSecondaryContainer),
    SAFE(R.string.badge_safe, R.drawable.ic_check, R.attr.colorSafeContainer, R.attr.colorOnSafeContainer),
    CAUTION(R.string.badge_caution, R.drawable.ic_warning, R.attr.colorCautionContainer, R.attr.colorOnCautionContainer),
    CONCERN(R.string.badge_concern, R.drawable.ic_error, MaterialR.attr.colorErrorContainer, MaterialR.attr.colorOnErrorContainer),
    PRIMARY(R.string.badge_primary, R.drawable.ic_star, MaterialR.attr.colorPrimaryContainer, MaterialR.attr.colorOnPrimaryContainer);

    companion object {
        /** Maps the `badgeStatus` XML enum (see attrs.xml) to a status. -1 means "no badge". */
        fun fromAttr(value: Int): BadgeStatus? = when (value) {
            0 -> AVAILABLE
            1 -> PREVIEW
            2 -> COMING_SOON
            3 -> SAFE
            4 -> CAUTION
            5 -> CONCERN
            6 -> SAMPLE
            else -> null
        }
    }
}
