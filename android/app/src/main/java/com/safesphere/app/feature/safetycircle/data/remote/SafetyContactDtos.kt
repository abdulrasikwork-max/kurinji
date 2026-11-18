package com.safesphere.app.feature.safetycircle.data.remote

import com.safesphere.app.feature.safetycircle.domain.ContactInput
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

// These classes mirror the JSON of /api/safety-contacts (see docs/API.md).
// Note: there is no userId anywhere. The backend identifies the owner from the JWT.

data class SafetyContactRequestDto(
    val name: String,
    val phoneNumber: String,
    val email: String?,
    val relationship: String,
    val priority: Int,
    val primary: Boolean,
    val enabled: Boolean
) {
    companion object {
        fun from(input: ContactInput) = SafetyContactRequestDto(
            name = input.name,
            phoneNumber = input.phoneNumber,
            email = input.email,
            relationship = input.relationship.name,
            priority = input.priority,
            primary = input.isPrimary,
            enabled = input.isEnabled
        )
    }
}

data class SafetyContactDto(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val email: String?,
    val relationship: String,
    val priority: Int,
    val primary: Boolean,
    val enabled: Boolean,
    /** N7 (D36); absent from an older server = false. */
    val inApp: Boolean? = null
) {
    fun toDomain() = SafetyContact(
        id = id,
        name = name,
        phoneNumber = phoneNumber,
        email = email,
        relationship = Relationship.fromApi(relationship),
        priority = priority,
        isPrimary = primary,
        isEnabled = enabled,
        inApp = inApp == true
    )
}
