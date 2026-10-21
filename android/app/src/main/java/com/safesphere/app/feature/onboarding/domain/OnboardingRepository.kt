package com.safesphere.app.feature.onboarding.domain

import kotlinx.coroutines.flow.Flow

/** Remembers whether the person has already seen the introduction screens. */
interface OnboardingRepository {
    val isOnboardingCompleted: Flow<Boolean>
    suspend fun markOnboardingCompleted()
}
