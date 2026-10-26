package com.safesphere.app.feature.onboarding.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.safesphere.app.R

/** One onboarding page. [tinted] = a one-colour icon drawn in the brand colour; otherwise the drawing keeps its own. */
data class OnboardingPage(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val tinted: Boolean = false
)

/**
 * Phase 13F (F19), words and pictures from Phase 16: see it first-hand, tap the butterfly to save (a private list), and
 * what the person controls. No safety pitch: one line says where trusted people and SOS live.
 */
object OnboardingPages {
    val all = listOf(
        OnboardingPage(R.drawable.ic_brand_mark, R.string.onboarding_connected_title, R.string.onboarding_connected_body),
        OnboardingPage(R.drawable.ic_nav_cocoon, R.string.onboarding_aware_title, R.string.onboarding_aware_body, tinted = true),
        OnboardingPage(R.drawable.ic_lock, R.string.onboarding_safe_title, R.string.onboarding_safe_body, tinted = true)
    )
}
