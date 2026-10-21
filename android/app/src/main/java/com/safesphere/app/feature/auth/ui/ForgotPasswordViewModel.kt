package com.safesphere.app.feature.auth.ui

import androidx.lifecycle.ViewModel
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.core.validation.FormValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ForgotPasswordUiState(
    val emailError: FieldError? = null,
    /** True after a valid email: the screen explains that nothing was actually sent. */
    val showPreviewNotice: Boolean = false
)

/** Phase 1 preview: sends nothing. Phase 2 will call AuthRepository.requestPasswordReset(). */
class ForgotPasswordViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ForgotPasswordUiState())
    val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

    fun onRequestResetClicked(email: String) {
        val emailError = FormValidator.validateEmail(email)
        _uiState.value = ForgotPasswordUiState(
            emailError = emailError,
            showPreviewNotice = emailError == null
        )
    }
}
