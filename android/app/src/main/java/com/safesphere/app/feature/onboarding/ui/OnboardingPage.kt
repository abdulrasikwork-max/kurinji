package com.safesphere.app.feature.onboarding.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.safesphere.app.R

/**
 * One onboarding page. [tinted] = a one-colour icon drawn in the brand colour; otherwise the drawing keeps its own.
 * [butterfly] = draw the reaction butterfly itself (Phase 17E: the page about the butterfly shows it, not a bookmark).
 */
data class OnboardingPage(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val tinted: Boolean = false,
    val butterfly: Boolean = false
)

/**
 * Phase 13F (F19), words and pictures from Phase 16 and 17E: see it first-hand, the butterfly (a reaction) and Save (a
 * private list), and what the person controls. No safety pitch: one line says where trusted people and SOS live.
 */
object OnboardingPages {
    val all = listOf(
        OnboardingPage(R.drawable.ic_brand_mark, R.string.onboarding_connected_title, R.string.onboarding_connected_body),
        OnboardingPage(R.drawable.ic_bookmark, R.string.onboarding_aware_title, R.string.onboarding_aware_body,
            butterfly = true),
        OnboardingPage(R.drawable.ic_lock, R.string.onboarding_safe_title, R.string.onboarding_safe_body, tinted = true)
    )
}
