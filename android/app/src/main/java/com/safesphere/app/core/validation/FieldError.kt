package com.safesphere.app.core.validation

/** Reasons a form field can be rejected. UI-independent so it can be unit tested. */
enum class FieldError {
    REQUIRED,
    INVALID_EMAIL,
    NAME_TOO_SHORT,
    /** Phase N2: a real name longer than 60 characters. */
    NAME_TOO_LONG,
    /** Phase N2: digits, emoji or symbols in a real name. */
    NOT_A_NAME,
    INVALID_PHONE,
    SELECTION_REQUIRED,
    /** Phase 13A: the date of birth is less than 18 years ago. */
    UNDER_AGE,
    /** Phase 13A: no date of birth chosen, a future date, or more than 120 years ago. */
    INVALID_DATE_OF_BIRTH,
    /** Phase 17B: a verification code that is not 6 digits. */
    INVALID_CODE
}
