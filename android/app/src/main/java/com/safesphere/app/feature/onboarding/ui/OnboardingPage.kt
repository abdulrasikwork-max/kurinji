package com.safesphere.app.feature.onboarding.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.safesphere.app.R

data class OnboardingPage(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
)

object OnboardingPages {
    val all = listOf(
        OnboardingPage(R.drawable.ic_group, R.string.onboarding_connected_title, R.string.onboarding_connected_body),
        OnboardingPage(R.drawable.ic_visibility, R.string.onboarding_aware_title, R.string.onboarding_aware_body),
        OnboardingPage(R.drawable.ic_shield, R.string.onboarding_safe_title, R.string.onboarding_safe_body)
    )
}
