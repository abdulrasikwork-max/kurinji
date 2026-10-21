package com.safesphere.app.feature.auth.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.clearErrorOnTextChange
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showError
import com.safesphere.app.databinding.FragmentRegisterBinding

class RegisterFragment :
    ViewBindingFragment<FragmentRegisterBinding>(FragmentRegisterBinding::inflate) {

    private val viewModel by viewModels<RegisterViewModel> { RegisterViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listOf(
            binding.fullNameLayout,
            binding.emailLayout,
            binding.passwordLayout,
            binding.confirmPasswordLayout
        ).forEach { it.clearErrorOnTextChange() }

        binding.registerButton.setOnClickListener { submit() }
        binding.confirmPasswordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.loginButton.setOnClickListener { findNavController().navigateUp() }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun submit() {
        viewModel.onRegisterClicked(
            fullName = binding.fullNameInput.text.toString(),
            email = binding.emailInput.text.toString(),
            password = binding.passwordInput.text.toString(),
            confirmPassword = binding.confirmPasswordInput.text.toString()
        )
    }

    private fun render(state: RegisterUiState) {
        binding.fullNameLayout.showError(state.fullNameError)
        binding.emailLayout.showError(state.emailError)
        binding.passwordLayout.showError(state.passwordError)
        binding.confirmPasswordLayout.showError(state.confirmPasswordError)

        binding.progress.isVisible = state.isLoading
        binding.registerButton.isEnabled = !state.isLoading

        val authError = state.authError
        binding.errorNotice.isVisible = authError != null
        if (authError != null) binding.errorNotice.setMessage(authError.toMessage(requireContext()))

        if (state.navigateToHome) {
            viewModel.onNavigationHandled()
            findNavController().navigate(R.id.action_register_to_home)
        }
    }
}
