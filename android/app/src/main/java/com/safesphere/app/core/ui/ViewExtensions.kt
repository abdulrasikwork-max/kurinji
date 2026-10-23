package com.safesphere.app.core.ui

import android.content.Context
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputLayout
import com.safesphere.app.R
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.core.validation.FormValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

fun FieldError.toMessage(context: Context): String = when (this) {
    FieldError.REQUIRED -> context.getString(R.string.error_field_required)
    FieldError.INVALID_EMAIL -> context.getString(R.string.error_invalid_email)
    FieldError.NAME_TOO_SHORT -> context.getString(R.string.error_name_too_short)
    FieldError.PASSWORD_TOO_SHORT ->
        context.getString(R.string.error_password_too_short, FormValidator.MIN_PASSWORD_LENGTH)
    FieldError.PASSWORDS_DO_NOT_MATCH -> context.getString(R.string.error_passwords_do_not_match)
    FieldError.INVALID_PHONE -> context.getString(R.string.error_invalid_phone)
    FieldError.SELECTION_REQUIRED -> context.getString(R.string.error_selection_required)
    FieldError.UNDER_AGE -> context.getString(R.string.error_under_age)
    FieldError.INVALID_DATE_OF_BIRTH -> context.getString(R.string.error_invalid_date_of_birth)
}

/** Shows [error] under the field, or clears the error when it is null. */
fun TextInputLayout.showError(error: FieldError?) {
    this.error = error?.toMessage(context)
}

/** Removes the error as soon as the user starts correcting the field. */
fun TextInputLayout.clearErrorOnTextChange() {
    editText?.doAfterTextChanged { this.error = null }
}

/**
 * Collects [flow] only while the Fragment's view is on screen (STARTED or later),
 * and stops automatically otherwise. Prevents work and crashes when the UI is not visible.
 */
fun <T> Fragment.collectWhenStarted(flow: Flow<T>, collector: (T) -> Unit) {
    val owner = viewLifecycleOwner
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect { collector(it) }
        }
    }
}
