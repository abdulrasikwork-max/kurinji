package com.safesphere.app.core.ui

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult

/** User-friendly text for each error. Raw server output is never shown; only our own 409 / 429 / 403 messages are. */
fun ApiResult.Failure.toMessage(context: Context): String {
    if (!message.isNullOrBlank() &&
        (error == ApiError.CONFLICT || error == ApiError.TOO_MANY_ATTEMPTS || error == ApiError.FORBIDDEN)
    ) return message
    return context.getString(
        when (error) {
            ApiError.UNAUTHORIZED -> R.string.error_session_expired
            ApiError.VALIDATION -> R.string.error_validation
            ApiError.NOT_FOUND -> R.string.error_not_found
            ApiError.CONFLICT -> R.string.error_conflict
            ApiError.NO_CONNECTION -> R.string.error_no_connection
            ApiError.SERVER -> R.string.error_server
            ApiError.UNKNOWN -> R.string.error_unknown
            ApiError.TOO_MANY_ATTEMPTS -> R.string.error_too_many_attempts
            ApiError.FORBIDDEN -> R.string.error_forbidden
        }
    )
}
