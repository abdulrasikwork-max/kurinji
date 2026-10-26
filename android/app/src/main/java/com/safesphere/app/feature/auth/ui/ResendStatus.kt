package com.safesphere.app.feature.auth.ui

import com.safesphere.app.feature.auth.domain.AuthError

/**
 * V25: where a "send the verification e-mail again" request stands. [Sent] only means the server took the request (it
 * answers the same for every account); the screen says so honestly.
 */
sealed interface ResendStatus {
    data object Idle : ResendStatus
    data object Sending : ResendStatus
    data object Sent : ResendStatus
    data class Failed(val error: AuthError) : ResendStatus
}
