package com.safesphere.app.core.validation

/**
 * Local, on-device checks for obvious input mistakes.
 * These are for user convenience only. A server must always validate again (Phase 2).
 * Each function returns null when the value is acceptable.
 */
object FormValidator {

    const val MIN_PASSWORD_LENGTH = 8
    private const val MIN_NAME_LENGTH = 2

    /** What a person may type: digits, spaces, ( ) . - and an optional leading +. */
    private val PHONE_TYPED_REGEX = Regex("^\\+?[0-9\\s().-]{7,30}$")

    /** After removing separators: optional +, then 7 to 15 digits (the E.164 maximum). Same rule as the backend. */
    private val PHONE_CANONICAL_REGEX = Regex("^\\+?[0-9]{7,15}$")

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validateRequired(value: String): FieldError? =
        if (value.isBlank()) FieldError.REQUIRED else null

    fun validateFullName(name: String): FieldError? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> FieldError.REQUIRED
            trimmed.length < MIN_NAME_LENGTH -> FieldError.NAME_TOO_SHORT
            else -> null
        }
    }

    fun validateEmail(email: String): FieldError? {
        val trimmed = email.trim()
        return when {
            trimmed.isEmpty() -> FieldError.REQUIRED
            !EMAIL_REGEX.matches(trimmed) -> FieldError.INVALID_EMAIL
            else -> null
        }
    }

    /** For registration: the password must be present and long enough. Passwords are never trimmed. */
    fun validateNewPassword(password: String): FieldError? = when {
        password.isEmpty() -> FieldError.REQUIRED
        password.length < MIN_PASSWORD_LENGTH -> FieldError.PASSWORD_TOO_SHORT
        else -> null
    }

    fun validateConfirmPassword(password: String, confirmation: String): FieldError? = when {
        confirmation.isEmpty() -> FieldError.REQUIRED
        confirmation != password -> FieldError.PASSWORDS_DO_NOT_MATCH
        else -> null
    }

    /** Required phone number in a believable format. */
    fun validatePhone(phone: String): FieldError? {
        val trimmed = phone.trim()
        return when {
            trimmed.isEmpty() -> FieldError.REQUIRED
            !PHONE_TYPED_REGEX.matches(trimmed) -> FieldError.INVALID_PHONE
            !PHONE_CANONICAL_REGEX.matches(trimmed.replace(Regex("[\\s().-]"), "")) -> FieldError.INVALID_PHONE
            else -> null
        }
    }

    /** Email is optional: empty is fine, but if something is typed it must be a valid address. */
    fun validateOptionalEmail(email: String): FieldError? =
        if (email.isBlank()) null else validateEmail(email)
}
