package com.safesphere.app.feature.account.data.remote

import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import com.safesphere.app.feature.auth.data.remote.PhoneChallengeDto
import com.safesphere.app.feature.auth.data.remote.UserDto
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * Phase 10A, reworked in N2: the signed-in person's own account (docs/API.md). The caller is always the token's account.
 * Sensitive actions carry a fresh code texted to the account's number ([CodeConfirmationDto]) instead of the password.
 */
interface AccountApi {

    /** N2: texts a fresh confirmation code to the account's own number. */
    @POST("api/users/me/confirmation")
    suspend fun requestConfirmation(): PhoneChallengeDto

    /** N2 (N-26): 200 with a new token for this phone; every other sign-in of the account ends. */
    @POST("api/users/me/sessions/sign-out-others")
    suspend fun signOutOtherPhones(@Body body: CodeConfirmationDto): AuthResponseDto

    /** N2 (N-24): the person's real name; answers the account as GET /api/users/me does. */
    @PUT("api/users/me/name")
    suspend fun changeName(@Body body: NameChangeDto): UserDto

    /**
     * Phase 10A.4: a fresh 24 h token for this sign-in (same session). Always granted during an SOS or Safe Walk; 409
     * when the sign-in is older than 30 days and nothing is running (the current token keeps working until it expires).
     */
    @POST("api/users/me/token")
    suspend fun renewToken(): AuthResponseDto

    /** 202; deletion happens no earlier than [DeletionScheduledDto.deletionDueAt]. */
    @POST("api/users/me/deletion")
    suspend fun requestDeletion(@Body body: CodeConfirmationDto): DeletionScheduledDto

    /** 204 (idempotent). */
    @POST("api/users/me/deletion/cancel")
    suspend fun cancelDeletion(@Body body: CodeConfirmationDto)
}

/** N2: the challenge from [AccountApi.requestConfirmation] and the code the person typed. */
data class CodeConfirmationDto(val challenge: String, val code: String) {
    override fun toString() = "CodeConfirmationDto(<hidden>)"
}

data class NameChangeDto(val name: String) {
    override fun toString() = "NameChangeDto(<hidden>)"
}

data class DeletionScheduledDto(val deletionRequestedAt: String?, val deletionDueAt: String?)
