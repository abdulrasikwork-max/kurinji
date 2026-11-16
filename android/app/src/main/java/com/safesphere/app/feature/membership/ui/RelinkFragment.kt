package com.safesphere.app.feature.membership.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentRelinkBinding

/**
 * Phase N3 (N-25): "I'm moving from another number", reached from "About you" (a new number with no account). Not
 * signed in. Shows the code for an IRL admin; afterwards the person signs in with this number. FLAG_SECURE.
 */
class RelinkFragment : ViewBindingFragment<FragmentRelinkBinding>(FragmentRelinkBinding::inflate) {

    private val viewModel by viewModels<RelinkViewModel> { RelinkViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mainButton.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.lost || state.code != null) {
                findNavController().navigate(R.id.action_global_to_login)
            } else {
                viewModel.onGetCode(binding.rollInput.text?.toString().orEmpty())
            }
        }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    private fun render(state: RelinkUiState) {
        val code = state.code
        binding.rollLayout.isVisible = code == null && !state.lost
        binding.rollLayout.error = if (state.rollProblem) getString(R.string.membership_roll_problem) else null
        binding.relinkIntro.setText(if (code == null) R.string.relink_intro else R.string.relink_code_title)
        binding.codeText.isVisible = code != null
        binding.codeText.text = code?.code.orEmpty()
        binding.codeText.contentDescription = code?.let {
            getString(R.string.membership_code_description, it.code.toList().joinToString(" "))
        }
        binding.codeHint.isVisible = code != null
        binding.progress.isVisible = state.busy
        binding.mainButton.isEnabled = !state.busy
        binding.mainButton.setText(when {
            code != null -> R.string.relink_sign_in
            state.lost -> R.string.action_enter_number_again
            else -> R.string.relink_get_code
        })
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null || (state.lost && code == null)
        when {
            failure != null && failure.message != null -> binding.errorNotice.setMessage(failure.toMessage(requireContext()))
            state.lost -> binding.errorNotice.setMessage(getString(R.string.register_lost))
            failure != null -> binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        }
    }
}
