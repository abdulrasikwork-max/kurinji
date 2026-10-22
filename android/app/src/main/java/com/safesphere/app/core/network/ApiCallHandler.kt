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
 * A 401 goes to [SessionEnder]. Phase 11 C1: an EXPIRED token is refreshed with the stored refresh secret and the call
 * is sent ONCE more with the new token (never twice: a second 401 means "sign in to sync", so no loop is possible).
 * If the server cannot be reached for the refresh, the call counts as offline (NO_CONNECTION). Only revocations
 * (password changed, account deleted / disabled) still sign the phone out (Phase 10A); a refused refresh keeps the
 * session and all local safety data and reports UNAUTHORIZED ("sign in to sync").
 */
class ApiCallHandler(private val sessionEnder: SessionEnder) {

    constructor(sessionStorage: SessionStorage) : this(SessionEnder(sessionStorage))

    suspend fun <T> execute(call: suspend () -> T): ApiResult<T> = attempt(call, afterRefresh = false)

    private suspend fun <T> attempt(call: suspend () -> T, afterRefresh: Boolean): ApiResult<T> {
        val failure = try {
            return ApiResult.Success(call())
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: HttpException) {
            e
        } catch (e: IOException) {
            return ApiResult.Failure(ApiError.NO_CONNECTION)
        } catch (e: Exception) {
            return ApiResult.Failure(ApiError.UNKNOWN) // for example an unreadable response body
        }
        val error = apiErrorForHttpCode(failure.code())
        if (error == ApiError.UNAUTHORIZED) {
            when (sessionEnder.onUnauthorized(failure.response(), afterRefresh)) {
                // A 401 is refused before the server does anything, so sending the same call again is safe.
                UnauthorizedOutcome.RETRY -> return attempt(call, afterRefresh = true)
                UnauthorizedOutcome.OFFLINE -> return ApiResult.Failure(ApiError.NO_CONNECTION)
                else -> Unit
            }
        }
        return failureFor(failure, error)
    }

    private fun failureFor(e: HttpException, error: ApiError): ApiResult.Failure {
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
