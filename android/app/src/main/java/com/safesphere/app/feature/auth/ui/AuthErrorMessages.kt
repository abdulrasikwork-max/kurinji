package com.safesphere.app.feature.auth.ui

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.feature.auth.domain.AuthError

/** User-friendly text for each error. Raw server messages are never shown (see [toMessage] with a server text). */
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
        AuthError.PHONE_NOT_VERIFIED -> R.string.error_phone_not_verified
        AuthError.PHONE_INVALID -> R.string.error_invalid_mobile
        AuthError.PHONE_REQUIRED -> R.string.error_phone_required
        AuthError.PHONE_IN_USE -> R.string.error_phone_in_use
        AuthError.PHONE_ALREADY_VERIFIED -> R.string.error_phone_already_verified
        AuthError.CHALLENGE_INVALID -> R.string.error_challenge_invalid
        AuthError.OTP_INVALID -> R.string.error_otp_invalid
        AuthError.OTP_EXPIRED -> R.string.error_otp_expired
        AuthError.OTP_LOCKED -> R.string.error_otp_locked
        AuthError.OTP_WAIT -> R.string.error_otp_wait
    }
)

/**
 * Phase 17B: [serverText] is set by the repository only for errors whose words depend on the case ("2 tries left",
 * "in 40 seconds"), all written by our own backend; anything else uses the app's own text.
 */
fun AuthError.toMessage(context: Context, serverText: String?): String =
    serverText?.takeIf { it.isNotBlank() } ?: toMessage(context)
