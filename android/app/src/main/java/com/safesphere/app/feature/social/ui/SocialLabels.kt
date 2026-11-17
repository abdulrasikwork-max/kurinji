package com.safesphere.app.feature.social.ui

import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.social.domain.ProfileInputPolicy

/** Phase 13A: the words for the form problems. The server's codes are never shown as text. */
object SocialLabels {

    @StringRes
    fun of(problem: ProfileInputPolicy.Problem): Int = when (problem) {
        ProfileInputPolicy.Problem.HANDLE_FORMAT -> R.string.social_problem_handle_format
        ProfileInputPolicy.Problem.HANDLE_UNAVAILABLE -> R.string.social_problem_handle_unavailable
        ProfileInputPolicy.Problem.BIO_TOO_LONG -> R.string.social_problem_bio_too_long
        ProfileInputPolicy.Problem.LINK -> R.string.social_problem_link
    }

    /** For date-of-birth errors shown outside a TextInputLayout. */
    @StringRes
    fun of(error: FieldError): Int = when (error) {
        FieldError.UNDER_AGE -> R.string.error_under_age
        FieldError.INVALID_DATE_OF_BIRTH -> R.string.error_invalid_date_of_birth
        else -> R.string.error_field_required
    }
}
