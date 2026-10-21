package com.safesphere.app.feature.auth.ui

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.feature.auth.domain.AuthError

/** User-friendly text for each error. Raw server messages are never shown. */
fun AuthError.toMessage(context: Context): String = context.getString(
    when (this) {
        AuthError.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
        AuthError.EMAIL_ALREADY_USED -> R.string.error_email_taken
        AuthError.VALIDATION -> R.string.error_validation
        AuthError.UNAUTHORIZED -> R.string.error_session_expired
        AuthError.NO_CONNECTION -> R.string.error_no_connection
        AuthError.SERVER -> R.string.error_server
        AuthError.UNKNOWN -> R.string.error_unknown
        AuthError.TOO_MANY_ATTEMPTS -> R.string.error_auth_too_many
    }
)
