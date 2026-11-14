package com.safesphere.app.feature.moderation.domain

/** Whether this account may moderate. The server decides, and re-checks its own database on every call. */
data class ModerationAccess(val canModerate: Boolean, val canAdminister: Boolean) {
    companion object {
        val NONE = ModerationAccess(canModerate = false, canAdminister = false)
    }
}
