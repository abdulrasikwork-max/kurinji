package com.safesphere.app.core.network

/** What can go wrong when the app calls the backend, in app terms (never raw HTTP or exceptions). */
enum class ApiError {
    UNAUTHORIZED,
    VALIDATION,
    NOT_FOUND,
    CONFLICT,
    NO_CONNECTION,
    SERVER,
    UNKNOWN,
    /** 429: too many failed attempts (e.g. wrong evidence-deletion passwords); try again later. */
    TOO_MANY_ATTEMPTS,
    /** 403 (Phase 7): signed in but not allowed, e.g. community contributions paused by a moderator. */
    FORBIDDEN
}

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    /**
     * [message] is only set for CONFLICT, TOO_MANY_ATTEMPTS and FORBIDDEN (for example "This phone number is already
     * in your Safety Circle").
     * Those texts are written by our own backend and are safe to show; every other error uses app text.
     * Phase 17A: [code] is the server's stable code for refusals the app reacts to (e.g. PROFILE_NEEDED), else null.
     */
    data class Failure(val error: ApiError, val message: String? = null, val code: String? = null) : ApiResult<Nothing>
}

/** True when the server rejected the saved token, so the person has to log in again. */
fun ApiResult<*>.isSessionExpired(): Boolean =
    this is ApiResult.Failure && error == ApiError.UNAUTHORIZED

/** Maps an HTTP status code to an [ApiError]. Pure function, covered by unit tests. */
fun apiErrorForHttpCode(code: Int): ApiError = when (code) {
    401 -> ApiError.UNAUTHORIZED
    403 -> ApiError.FORBIDDEN
    404 -> ApiError.NOT_FOUND
    409 -> ApiError.CONFLICT
    429 -> ApiError.TOO_MANY_ATTEMPTS
    400, 422 -> ApiError.VALIDATION
    in 500..599 -> ApiError.SERVER
    else -> ApiError.UNKNOWN
}
