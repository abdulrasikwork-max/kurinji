package com.safesphere.app.feature.account.data.remote

import com.safesphere.app.feature.auth.data.remote.AuthResponseDto
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.PUT

/** Phase 10A: the signed-in person's own account (docs/API.md). The caller is always the token's account. */
interface AccountApi {

    /** 200 with a new token for this phone; every other session of the account ends. */
    @PUT("api/users/me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequestDto): AuthResponseDto

    /** 202; deletion happens no earlier than [DeletionScheduledDto.deletionDueAt]. */
    @POST("api/users/me/deletion")
    suspend fun requestDeletion(@Body body: PasswordConfirmationDto): DeletionScheduledDto

    /** 204 (idempotent). */
    @POST("api/users/me/deletion/cancel")
    suspend fun cancelDeletion(@Body body: PasswordConfirmationDto)
}

data class ChangePasswordRequestDto(val currentPassword: String, val newPassword: String) {
    override fun toString() = "ChangePasswordRequestDto(<hidden>)"
}

data class PasswordConfirmationDto(val password: String) {
    override fun toString() = "PasswordConfirmationDto(<hidden>)"
}

data class DeletionScheduledDto(val deletionRequestedAt: String?, val deletionDueAt: String?)
