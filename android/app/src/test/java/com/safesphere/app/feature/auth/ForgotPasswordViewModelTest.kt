package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.ui.ForgotPasswordViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgotPasswordViewModelTest {

    private val viewModel = ForgotPasswordViewModel()

    @Test
    fun `invalid email shows an error and no preview notice`() {
        viewModel.onRequestResetClicked("nope")

        assertEquals(FieldError.INVALID_EMAIL, viewModel.uiState.value.emailError)
        assertFalse(viewModel.uiState.value.showPreviewNotice)
    }

    @Test
    fun `valid email shows the preview notice because nothing is sent`() {
        viewModel.onRequestResetClicked("user@example.com")

        assertNull(viewModel.uiState.value.emailError)
        assertTrue(viewModel.uiState.value.showPreviewNotice)
    }
}
