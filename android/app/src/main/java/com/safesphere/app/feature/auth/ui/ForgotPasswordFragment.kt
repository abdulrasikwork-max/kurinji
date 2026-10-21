package com.safesphere.app.feature.auth.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.clearErrorOnTextChange
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showError
import com.safesphere.app.databinding.FragmentForgotPasswordBinding

class ForgotPasswordFragment :
    ViewBindingFragment<FragmentForgotPasswordBinding>(FragmentForgotPasswordBinding::inflate) {

    private val viewModel by viewModels<ForgotPasswordViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.emailLayout.clearErrorOnTextChange()
        binding.requestResetButton.setOnClickListener { submit() }
        binding.emailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun submit() {
        viewModel.onRequestResetClicked(binding.emailInput.text.toString())
    }

    private fun render(state: ForgotPasswordUiState) {
        binding.emailLayout.showError(state.emailError)
        binding.previewNotice.isVisible = state.showPreviewNotice
    }
}
