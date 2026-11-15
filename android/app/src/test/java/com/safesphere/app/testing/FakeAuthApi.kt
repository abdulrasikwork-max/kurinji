package com.safesphere.app.testing

import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.PhoneChallengeDto
import com.safesphere.app.feature.auth.data.remote.PhoneChangeRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneResendRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneStartRequestDto
import com.safesphere.app.feature.auth.data.remote.PhoneVerifyRequestDto
import com.safesphere.app.feature.auth.data.remote.RefreshRequestDto
import com.safesphere.app.feature.auth.data.remote.SignUpRequestDto
import com.safesphere.app.feature.auth.data.remote.SignUpTicketDto
import com.safesphere.app.feature.auth.data.remote.UserDto
import com.safesphere.app.feature.auth.data.remote.VerifyResponseDto
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * A fake server. Each call either returns the configured value or throws the configured exception,
 * exactly as Retrofit would (HttpException for non-2xx answers, IOException for network problems).
 * Phase N2: phone number and code only.
 */
class FakeAuthApi : AuthApi {

    var startOutcome: () -> PhoneChallengeDto = { challengeDto() }
    var verifyOutcome: () -> VerifyResponseDto = { signedIn() }
    var signUpOutcome: () -> AuthResponseDto = { authResponse(token = "new-account-token", refresh = "new-refresh") }
    var resendOutcome: () -> PhoneChallengeDto = { challengeDto() }
    var changeOutcome: () -> PhoneChallengeDto = { challengeDto() }
    var lastStartRequest: PhoneStartRequestDto? = null
        private set
    var lastVerifyRequest: PhoneVerifyRequestDto? = null
        private set
    var lastSignUpRequest: SignUpRequestDto? = null
        private set
    var meOutcome: () -> UserDto = { userDto() }

    var meCalls = 0
        private set

    override suspend fun startSignIn(body: PhoneStartRequestDto): PhoneChallengeDto {
        lastStartRequest = body
        return startOutcome()
    }

    override suspend fun verifyCode(body: PhoneVerifyRequestDto): VerifyResponseDto {
        lastVerifyRequest = body
        return verifyOutcome()
    }

    override suspend fun signUp(body: SignUpRequestDto): AuthResponseDto {
        lastSignUpRequest = body
        return signUpOutcome()
    }

    override suspend fun resendCode(body: PhoneResendRequestDto): PhoneChallengeDto = resendOutcome()

    override suspend fun changePhone(body: PhoneChangeRequestDto): PhoneChallengeDto = changeOutcome()

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
        fun userDto(name: String = "Asha Devi", id: Long = 7, phoneHint: String? = "••••••3210") =
            UserDto(id = id, name = name, phoneHint = phoneHint, createdAt = "2026-10-15T10:00:00Z")

        fun authResponse(token: String = "test-token", refresh: String? = null, user: UserDto = userDto()) = AuthResponseDto(
            accessToken = token, tokenType = "Bearer", expiresInSeconds = 3600, user = user, refreshToken = refresh
        )

        /** N2: the right code for a number with an account. */
        fun signedIn(session: AuthResponseDto = authResponse(token = "verified-token", refresh = "new-refresh")) =
            VerifyResponseDto(status = VerifyResponseDto.SIGNED_IN, session = session)

        /** N2: the right code for a number without an account. */
        fun signUpNeeded(ticket: String? = "k".repeat(43)) = VerifyResponseDto(status = VerifyResponseDto.SIGN_UP_NEEDED,
            signUp = SignUpTicketDto(ticket = ticket, phoneHint = "••••••3210", expiresInSeconds = 1800))

        /** A code was sent to ••••••3210. */
        fun challengeDto(token: String? = "c".repeat(43)) = PhoneChallengeDto(
            challenge = token, phoneHint = "••••••3210", codeSent = true, resendInSeconds = 60, codeExpiresInSeconds = 300
        )

        /** What Retrofit throws for a non-2xx answer. */
        fun httpError(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

        /** A non-2xx answer with our ApiError body, its stable `code` and the server's [message]. */
        fun httpError(code: Int, errorCode: String, message: String = "x") = HttpException(
            Response.error<Any>(code,
                "{\"status\":$code,\"message\":\"$message\",\"code\":\"$errorCode\"}".toResponseBody(null))
        )

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
