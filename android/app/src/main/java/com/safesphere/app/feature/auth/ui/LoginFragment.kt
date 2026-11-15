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

        binding.phoneLayout.clearErrorOnTextChange()

        binding.loginButton.setOnClickListener { submit() }
        binding.phoneInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
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
        viewModel.onSendCodeClicked(binding.phoneInput.text.toString())
    }

    private fun render(state: LoginUiState) {
        binding.phoneLayout.showError(state.phoneError)

        // Loading: show the progress bar and block double taps.
        binding.progress.isVisible = state.isLoading
        binding.loginButton.isEnabled = !state.isLoading

        // Server / network problems appear in the notice above the button.
        val authError = state.authError
        binding.errorNotice.isVisible = authError != null
        if (authError != null) binding.errorNotice.setMessage(authError.toMessage(requireContext(), state.authMessage))

        // Phase N2: a code is on its way: the code screen (this one stays behind it for "back").
        if (state.navigateToVerification) {
            viewModel.onNavigationHandled()
            findNavController().navigate(R.id.action_login_to_phone_verify)
        }
    }
}
