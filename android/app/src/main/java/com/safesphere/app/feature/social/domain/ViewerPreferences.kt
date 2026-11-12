package com.safesphere.app.feature.social.domain

import kotlinx.coroutines.flow.Flow

/**
 * Phase 13E (E20, design §8.1 layer 1 "YOU"): the viewer's own choices about what they read. It describes the viewer
 * only, never anyone else, and stays on this phone.
 */
interface ViewerPreferences {

    /** Posts and perspectives the automatic check marked as strong language are shown behind a tap. Default off. */
    val collapseStrongLanguage: Flow<Boolean>

    suspend fun setCollapseStrongLanguage(on: Boolean)

    /**
     * Phase 17G-3: the account (user id) that agreed, on this phone, to send its typed Explore words to the optional smart
     * understanding; null = nobody. It is tied to one account on purpose: someone else signing in on this phone has NOT
     * agreed, so [SmartUnderstandingChoice] never treats it as theirs.
     */
    val smartUnderstandingAccount: Flow<Long?>

    suspend fun setSmartUnderstandingAccount(userId: Long?)

    /**
     * IRL redesign (R6): "<account id>:<city code>" of the Home city the person chose on this phone; null = none. Read and
     * written only through [HomeCityChoice], which ignores another account's value.
     */
    val homeCity: Flow<String?>

    suspend fun setHomeCity(value: String?)
}
