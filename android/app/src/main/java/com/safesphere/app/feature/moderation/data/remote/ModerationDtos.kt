package com.safesphere.app.feature.moderation.data.remote

import com.safesphere.app.feature.moderation.domain.ModerationAccess

/** Anything unreadable means "no access" (fail closed). */
data class AccessDto(val canModerate: Boolean?, val canAdminister: Boolean?) {
    fun toDomain() = ModerationAccess(canModerate == true, canAdminister == true)
}

/** The body of a decision on a request to look again (used by the content-moderation API). */
data class AppealDecisionDto(val decision: String)
