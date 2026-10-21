package com.safesphere.app.feature.auth.data.remote

import com.safesphere.app.feature.auth.domain.AuthUser

// These classes mirror the JSON of the Spring Boot API (see docs/API.md).

data class RegisterRequestDto(val name: String, val email: String, val password: String) {
    // Data classes print all fields in toString(). Redact so a stray log line can never leak a password.
    override fun toString() = "RegisterRequestDto(name=$name, email=<hidden>, password=<hidden>)"
}

data class LoginRequestDto(val email: String, val password: String) {
    override fun toString() = "LoginRequestDto(email=<hidden>, password=<hidden>)"
}

data class UserDto(val id: Long, val name: String, val email: String, val createdAt: String?) {
    fun toDomain() = AuthUser(id = id, name = name, email = email)
}

data class AuthResponseDto(
    val accessToken: String,
    val tokenType: String?,
    val expiresInSeconds: Long?,
    val user: UserDto
) {
    // The token is a credential: keep it out of logs too.
    override fun toString() = "AuthResponseDto(accessToken=<hidden>, user=$user)"
}
