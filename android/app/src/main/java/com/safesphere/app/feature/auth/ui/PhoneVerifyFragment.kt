package com.safesphere.app.feature.auth.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showError
import com.safesphere.app.databinding.FragmentPhoneVerifyBinding

/**
 * Phase 17B: "Enter the code" (and "Add your mobile number" for an account without one). The code is sent as soon as
 * six digits are typed or pasted (the keyboard can offer it from the SMS: autofill hint smsOTPCode). Nothing is stored.
 */
class PhoneVerifyFragment : ViewBindingFragment<FragmentPhoneVerifyBinding>(FragmentPhoneVerifyBinding::inflate) {

    private val viewModel by viewModels<PhoneVerifyViewModel> { PhoneVerifyViewModel.factory(appContainer) }
    private var lastShownMessage: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.codeInput.doAfterTextChanged { viewModel.onCodeChanged(it?.toString().orEmpty()) }
        binding.codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.onVerifyClicked(binding.codeInput.text.toString())
                true
            } else {
                false
            }
        }
        binding.verifyButton.setOnClickListener { viewModel.onVerifyClicked(binding.codeInput.text.toString()) }
        binding.resendButton.setOnClickListener {
            binding.codeInput.text = null
            viewModel.onResendClicked()
        }
        binding.useAnotherNumberButton.setOnClickListener { viewModel.onUseAnotherNumber() }
        binding.sendCodeButton.setOnClickListener { submitNumber() }
        binding.phoneInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitNumber()
                true
            } else {
                false
            }
        }
        binding.cancelNumberButton.setOnClickListener { viewModel.onCancelNumberChange() }
        binding.backToSignInButton.setOnClickListener { findNavController().navigate(R.id.action_global_to_login) }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun submitNumber() {
        binding.codeInput.text = null
        viewModel.onSubmitNumber(binding.phoneInput.text.toString())
    }

    private fun render(state: PhoneVerifyUiState) {
        binding.lostBlock.isVisible = state.lost
        binding.numberBlock.isVisible = !state.lost && state.askingForNumber
        binding.codeBlock.isVisible = !state.lost && !state.askingForNumber
        binding.cancelNumberButton.isVisible = state.canCancelNumberChange

        val hint = state.phoneHint.orEmpty()
        binding.sentToText.text = getString(
            if (state.codeSent) R.string.phone_verify_sent_to else R.string.phone_verify_not_sent, "+91 $hint")
        binding.codeLayout.showError(state.codeError)
        binding.phoneLayout.showError(state.phoneError)

        binding.resendButton.isEnabled = state.canResend
        binding.resendButton.text = if (state.resendInSeconds > 0) {
            getString(R.string.action_send_new_code_in, state.resendInSeconds.toInt())
        } else {
            getString(R.string.action_send_new_code)
        }
        binding.verifyButton.isEnabled = !state.isBusy
        binding.sendCodeButton.isEnabled = !state.isBusy
        binding.progress.isVisible = state.isBusy

        val error = state.authError
        binding.errorNotice.isVisible = error != null && !state.lost
        if (error != null) binding.errorNotice.setMessage(error.toMessage(requireContext(), state.authMessage))
        // Found on the A23: the keyboard covered the message ("2 tries left"). Bring each new message into view.
        val shownMessage = error?.let { "$it:${state.authMessage}" }
        if (shownMessage != null && shownMessage != lastShownMessage) {
            binding.errorNotice.post {
                binding.errorNotice.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, binding.errorNotice.width, binding.errorNotice.height), false)
            }
        }
        lastShownMessage = shownMessage
        if (state.newCodeSent) {
            binding.infoNotice.isVisible = true
            viewModel.onNewCodeShown()
        } else if (error != null) {
            binding.infoNotice.isVisible = false
        }

        when (state.done) {
            AfterVerification.MAKE_PROFILE -> {
                viewModel.onDoneHandled()
                findNavController().navigate(R.id.action_phone_verify_to_profile)
            }
            AfterVerification.HOME -> {
                viewModel.onDoneHandled()
                findNavController().navigate(R.id.action_phone_verify_to_start)
            }
            null -> Unit
        }
    }
}
