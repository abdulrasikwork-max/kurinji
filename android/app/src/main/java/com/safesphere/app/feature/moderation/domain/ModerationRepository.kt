package com.safesphere.app.feature.moderation.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Whether to offer the moderator screens at all (any signed-in account may ask). The server checks the caller's role
 * from its database on EVERY call (403 otherwise); what the app shows or hides is only convenience. The queue and the
 * cases themselves are in [ContentModerationRepository]. (The Phase 7 Nearby-signal cases were retired in N1.)
 */
interface ModerationRepository {
    suspend fun access(): ApiResult<ModerationAccess>
}
