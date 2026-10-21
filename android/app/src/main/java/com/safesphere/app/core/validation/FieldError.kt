package com.safesphere.app.core.validation

/** Reasons a form field can be rejected. UI-independent so it can be unit tested. */
enum class FieldError {
    REQUIRED,
    INVALID_EMAIL,
    NAME_TOO_SHORT,
    PASSWORD_TOO_SHORT,
    PASSWORDS_DO_NOT_MATCH,
    INVALID_PHONE,
    SELECTION_REQUIRED
}
