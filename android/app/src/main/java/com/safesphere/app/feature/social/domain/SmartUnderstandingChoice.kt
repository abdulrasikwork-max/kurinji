package com.safesphere.app.feature.social.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Phase 17G-3: whether the CURRENT account has agreed to send its typed Explore words to the optional smart understanding.
 * Off by default. The agreement is stored with the account that gave it, so a different account signing in on the same phone
 * starts with it off (nobody agrees for someone else). Turning it off forgets it. Nothing but this one id is stored.
 */
class SmartUnderstandingChoice(
    private val preferences: ViewerPreferences,
    private val currentUserId: () -> Long?
) {
    /** True only while the account that agreed is the account signed in now. */
    val enabled: Flow<Boolean> = preferences.smartUnderstandingAccount.map { agreed ->
        val me = currentUserId()
        agreed != null && me != null && agreed == me
    }

    suspend fun isEnabled(): Boolean = enabled.first()

    /** The person confirmed the question (or switched it off). Without a signed-in account nothing is stored. */
    suspend fun set(on: Boolean) {
        if (!on) {
            preferences.setSmartUnderstandingAccount(null)
            return
        }
        currentUserId()?.let { preferences.setSmartUnderstandingAccount(it) }
    }
}
