package com.safesphere.app.testing

import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.LoginRequestDto
import com.safesphere.app.feature.auth.data.remote.RefreshRequestDto
import com.safesphere.app.feature.auth.data.remote.RegisterRequestDto
import com.safesphere.app.feature.auth.data.remote.UserDto
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * A fake server. Each call either returns the configured value or throws the configured exception,
 * exactly as Retrofit would (HttpException for non-2xx answers, IOException for network problems).
 */
class FakeAuthApi : AuthApi {

    var registerOutcome: () -> AuthResponseDto = { authResponse() }
    var loginOutcome: () -> AuthResponseDto = { authResponse() }
    var meOutcome: () -> UserDto = { userDto() }

    var meCalls = 0
        private set
    var lastLoginRequest: LoginRequestDto? = null
        private set
    var lastRegisterRequest: RegisterRequestDto? = null
        private set

    override suspend fun register(body: RegisterRequestDto): AuthResponseDto {
        lastRegisterRequest = body
        return registerOutcome()
    }

    override suspend fun login(body: LoginRequestDto): AuthResponseDto {
        lastLoginRequest = body
        return loginOutcome()
    }

    override suspend fun me(): UserDto {
        meCalls++
        return meOutcome()
    }

    // ---- Phase 11 C1 ----

    var refreshOutcome: (String) -> AuthResponseDto = { authResponse(token = "refreshed-token", refresh = "rotated-refresh") }
    var refreshCalls = 0
        private set
    val refreshTokensSeen = mutableListOf<String>()
    /** Runs inside refresh() before it answers (to hold a refresh in flight in a test). */
    var beforeRefreshAnswer: suspend () -> Unit = {}
    var signOutOutcome: () -> Unit = {}
    val signedOutTokens = mutableListOf<String>()

    override suspend fun refresh(body: RefreshRequestDto): AuthResponseDto {
        refreshCalls++
        refreshTokensSeen += body.refreshToken
        beforeRefreshAnswer()
        return refreshOutcome(body.refreshToken)
    }

    override suspend fun signOut(body: RefreshRequestDto) {
        signedOutTokens += body.refreshToken
        signOutOutcome()
    }

    companion object {
        fun userDto(name: String = "Asha Devi") =
            UserDto(id = 7, name = name, email = "asha@example.com", createdAt = "2026-10-15T10:00:00Z")

        fun authResponse(token: String = "test-token", refresh: String? = null, user: UserDto = userDto()) = AuthResponseDto(
            accessToken = token, tokenType = "Bearer", expiresInSeconds = 3600, user = user, refreshToken = refresh
        )

        /** What Retrofit throws for a non-2xx answer. */
        fun httpError(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

        /** A 401 with the server's RFC 6750 reason, as the refresh endpoint sends it. */
        fun refused(reason: String): HttpException {
            val raw = okhttp3.Response.Builder()
                .request(okhttp3.Request.Builder().url("http://localhost/api/auth/refresh").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .header("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"$reason\"")
                .build()
            return HttpException(Response.error<Any>("{}".toResponseBody(null), raw))
        }
    }
}
