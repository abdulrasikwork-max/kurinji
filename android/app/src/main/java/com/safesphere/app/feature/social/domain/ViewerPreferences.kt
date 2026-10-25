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
}
