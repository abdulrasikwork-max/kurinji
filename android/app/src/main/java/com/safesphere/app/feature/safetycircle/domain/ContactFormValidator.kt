package com.safesphere.app.feature.safetycircle.domain

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.core.validation.FormValidator

/** The problems found in the Add / Edit contact form. A null field means "fine". */
data class ContactFormErrors(
    val name: FieldError? = null,
    val phone: FieldError? = null,
    val email: FieldError? = null,
    val relationship: FieldError? = null,
    val priority: FieldError? = null
) {
    val hasErrors: Boolean
        get() = name != null || phone != null || email != null || relationship != null || priority != null
}

/**
 * Local, on-device checks for the contact form (the server checks again). Pure Kotlin so it can be
 * unit tested without Android.
 */
object ContactFormValidator {

    const val MIN_PRIORITY = 1
    const val MAX_PRIORITY = 5
    const val DEFAULT_PRIORITY = 3

    fun validate(
        name: String,
        phone: String,
        email: String,
        relationship: Relationship?,
        priority: Int?
    ): ContactFormErrors = ContactFormErrors(
        name = FormValidator.validateRequired(name),
        phone = FormValidator.validatePhone(phone),
        email = FormValidator.validateOptionalEmail(email),
        relationship = if (relationship == null) FieldError.SELECTION_REQUIRED else null,
        priority = if (priority == null || priority !in MIN_PRIORITY..MAX_PRIORITY) FieldError.SELECTION_REQUIRED else null
    )
}
