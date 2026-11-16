package com.safesphere.app.feature.auth.ui

import android.os.Bundle
import android.view.View
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
import com.safesphere.app.feature.social.ui.DateOfBirthPicker
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase N2: "About you", shown only after the right code for a number that has no account yet: the real name and the
 * date of birth (18+, stored nowhere). Creating the account signs this phone in; the profile (handle) comes next.
 */
class RegisterFragment :
    ViewBindingFragment<FragmentRegisterBinding>(FragmentRegisterBinding::inflate) {

    private val viewModel by viewModels<RegisterViewModel> { RegisterViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fullNameLayout.clearErrorOnTextChange()
        binding.dateOfBirthInput.setOnClickListener { pickDateOfBirth() }
        DateOfBirthPicker.reattach(this, PICKER_TAG, viewModel::onDateOfBirthChosen)

        binding.registerButton.setOnClickListener {
            if (viewModel.uiState.value.lost) {
                findNavController().navigate(R.id.action_global_to_login)
            } else {
                viewModel.onCreateClicked(binding.fullNameInput.text.toString())
            }
        }

        // N3 (N-25): the ticket stays in memory and is used by the re-link screen instead of a new account.
        binding.movingButton.setOnClickListener { findNavController().navigate(R.id.action_register_to_relink) }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun pickDateOfBirth() {
        DateOfBirthPicker.show(this, PICKER_TAG, viewModel.uiState.value.dateOfBirth, viewModel.today(),
            viewModel::onDateOfBirthChosen)
    }

    private fun render(state: RegisterUiState) {
        binding.fullNameLayout.showError(state.fullNameError)
        binding.dateOfBirthLayout.showError(state.dateOfBirthError)

        val shown = state.dateOfBirth?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)).orEmpty()
        if (binding.dateOfBirthInput.text.toString() != shown) binding.dateOfBirthInput.setText(shown)

        binding.progress.isVisible = state.isLoading
        binding.registerButton.isEnabled = !state.isLoading
        binding.movingButton.isVisible = !state.lost
        binding.movingButton.isEnabled = !state.isLoading
        binding.fullNameLayout.isEnabled = !state.lost
        binding.dateOfBirthLayout.isEnabled = !state.lost
        binding.registerButton.setText(if (state.lost) R.string.action_enter_number_again else R.string.action_create_account)

        // The sign-up ended (the app was closed, or the ticket was used or ran out): the number again, a new code.
        val authError = state.authError
        binding.errorNotice.isVisible = authError != null || state.lost
        when {
            state.lost -> binding.errorNotice.setMessage(getString(R.string.register_lost))
            authError != null -> binding.errorNotice.setMessage(authError.toMessage(requireContext(), state.authMessage))
        }

        if (state.navigateToProfile) {
            viewModel.onNavigationHandled()
            findNavController().navigate(R.id.action_register_to_profile)
        }
    }

    private companion object {
        const val PICKER_TAG = "register_date_of_birth"
    }
}
