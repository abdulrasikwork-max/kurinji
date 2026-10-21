package com.safesphere.app.testing

import com.safesphere.app.feature.auth.data.remote.AuthApi
import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.LoginRequestDto
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

    companion object {
        fun userDto(name: String = "Asha Devi") =
            UserDto(id = 7, name = name, email = "asha@example.com", createdAt = "2026-10-15T10:00:00Z")

        fun authResponse(token: String = "test-token") = AuthResponseDto(
            accessToken = token, tokenType = "Bearer", expiresInSeconds = 3600, user = userDto()
        )

        /** What Retrofit throws for a non-2xx answer. */
        fun httpError(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))
    }
}
