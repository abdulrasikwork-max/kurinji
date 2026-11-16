package com.safesphere.app.feature.membership.ui.staff

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentStaffActionsBinding

/**
 * Phase N3 (A2): an admin moves a member's account to a new number (N-25: in person, the code on the new phone + the
 * roll number typed from the ID card), ends a membership, or corrects a checked name (logged, shown to the person).
 * Each asks for confirmation; the server refuses the admin's own account. FLAG_SECURE.
 */
class StaffActionsFragment : ViewBindingFragment<FragmentStaffActionsBinding>(FragmentStaffActionsBinding::inflate) {

    private val viewModel by viewModels<StaffAdminViewModel> { StaffAdminViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.relinkButton.setOnClickListener {
            confirm(R.string.staff_relink_confirm) {
                viewModel.relink(binding.relinkCodeInput.text?.toString().orEmpty(), binding.relinkRollInput.text?.toString().orEmpty())
            }
        }
        binding.endButton.setOnClickListener {
            confirm(R.string.staff_end_confirm) { viewModel.endMembership(binding.endPhoneInput.text?.toString().orEmpty()) }
        }
        binding.nameButton.setOnClickListener {
            confirm(R.string.staff_name_confirm) {
                viewModel.correctName(binding.namePhoneInput.text?.toString().orEmpty(), binding.nameInput.text?.toString().orEmpty())
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

    private fun render(state: StaffAdminUiState) {
        binding.progress.isVisible = state.busy
        listOf(binding.relinkButton, binding.endButton, binding.nameButton).forEach { it.isEnabled = !state.busy }
        val phoneProblem = if (StaffField.PHONE in state.problems) getString(R.string.staff_phone_problem) else null
        binding.relinkCodeLayout.error = if (StaffField.CODE in state.problems) getString(R.string.staff_code_problem) else null
        binding.relinkRollLayout.error = if (StaffField.ROLL in state.problems) getString(R.string.membership_roll_problem) else null
        // The phone problem belongs to whichever form was sent last; both fields show it until the next send.
        binding.endPhoneLayout.error = phoneProblem
        binding.namePhoneLayout.error = phoneProblem
        binding.nameLayout.error = if (StaffField.NAME in state.problems) getString(R.string.staff_name_problem) else null

        state.done?.let { done ->
            Toast.makeText(requireContext(), when (done) {
                StaffDone.RELINKED -> R.string.staff_done_relinked
                StaffDone.ENDED -> R.string.staff_done_ended
                StaffDone.NAME_CORRECTED -> R.string.staff_done_name
                else -> R.string.staff_done_generic
            }, Toast.LENGTH_LONG).show()
            when (done) {
                StaffDone.RELINKED -> { binding.relinkCodeInput.setText(""); binding.relinkRollInput.setText("") }
                StaffDone.ENDED -> binding.endPhoneInput.setText("")
                StaffDone.NAME_CORRECTED -> { binding.namePhoneInput.setText(""); binding.nameInput.setText("") }
                else -> Unit
            }
            viewModel.onDoneShown()
        }
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun confirm(message: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(R.string.staff_confirm) { _, _ -> action() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
