package com.safesphere.app.feature.auth.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.clearErrorOnTextChange
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showError
import com.safesphere.app.databinding.FragmentLoginBinding

class LoginFragment : ViewBindingFragment<FragmentLoginBinding>(FragmentLoginBinding::inflate) {

    private val viewModel by viewModels<LoginViewModel> { LoginViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.emailLayout.clearErrorOnTextChange()
        binding.passwordLayout.clearErrorOnTextChange()

        binding.loginButton.setOnClickListener { submit() }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.forgotPasswordButton.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_forgot_password)
        }
        binding.registerButton.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_register)
        }

        binding.removeDeletedAccountDataButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.login_remove_deleted_account_data)
                .setMessage(R.string.login_remove_deleted_account_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_remove) { _, _ -> viewModel.onRemoveDeletedAccountData() }
                .show()
        }

        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(viewModel.deletedAccountData) { show ->
            binding.deletedAccountNotice.isVisible = show
            binding.removeDeletedAccountDataButton.isVisible = show
        }
    }

    private fun submit() {
        viewModel.onLoginClicked(
            email = binding.emailInput.text.toString(),
            password = binding.passwordInput.text.toString()
        )
    }

    private fun render(state: LoginUiState) {
        binding.emailLayout.showError(state.emailError)
        binding.passwordLayout.showError(state.passwordError)

        // Loading: show the progress bar and block double taps.
        binding.progress.isVisible = state.isLoading
        binding.loginButton.isEnabled = !state.isLoading

        // Server / network problems appear in the notice above the button.
        val authError = state.authError
        binding.errorNotice.isVisible = authError != null
        if (authError != null) binding.errorNotice.setMessage(authError.toMessage(requireContext()))

        if (state.navigateToHome) {
            viewModel.onNavigationHandled()
            findNavController().navigate(R.id.action_login_to_start)
        }
    }
}
