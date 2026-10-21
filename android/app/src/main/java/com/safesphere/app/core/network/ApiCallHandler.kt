package com.safesphere.app.core.network

import com.google.gson.Gson
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.HttpException

/**
 * Runs one backend call and turns every outcome into an [ApiResult]. Used by every repository added
 * after Phase 2, so error handling is written once.
 *
 * Session expiry reuses the Phase 2 approach: on a 401 the saved session is cleared, and the screen then shows the
 * "Session expired" dialog and returns to Login. Since Phase 10A the clearing goes through [SessionEnder]: only when
 * the rejected token is still the saved one, and with the server's reason (password changed, account deleted).
 */
class ApiCallHandler(private val sessionEnder: SessionEnder) {

    constructor(sessionStorage: SessionStorage) : this(SessionEnder(sessionStorage))

    suspend fun <T> execute(call: suspend () -> T): ApiResult<T> =
        try {
            ApiResult.Success(call())
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: HttpException) {
            failureFor(e)
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.NO_CONNECTION)
        } catch (e: Exception) {
            ApiResult.Failure(ApiError.UNKNOWN) // for example an unreadable response body
        }

    private suspend fun failureFor(e: HttpException): ApiResult.Failure {
        val error = apiErrorForHttpCode(e.code())
        if (error == ApiError.UNAUTHORIZED) sessionEnder.onUnauthorized(e.response())
        val message = if (error in SERVER_WORDED) readServerMessage(e) else null
        return ApiResult.Failure(error, message)
    }

    private fun readServerMessage(e: HttpException): String? =
        try {
            Gson().fromJson(e.response()?.errorBody()?.string(), ErrorBody::class.java)?.message
        } catch (ignored: Exception) {
            null
        }

    /** The part of the backend's standard error JSON we care about. */
    private data class ErrorBody(val message: String? = null)

    private companion object {
        /** Errors whose text our own backend writes for people (safe to show); every other error uses app text. */
        val SERVER_WORDED = setOf(ApiError.CONFLICT, ApiError.TOO_MANY_ATTEMPTS, ApiError.FORBIDDEN)
    }
}
