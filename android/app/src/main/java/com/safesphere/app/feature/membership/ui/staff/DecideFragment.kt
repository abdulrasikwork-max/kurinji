package com.safesphere.app.feature.membership.ui.staff

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentStaffDecideBinding
import com.safesphere.app.feature.membership.domain.DecisionOutcome
import com.safesphere.app.feature.membership.domain.RefusalReason
import com.safesphere.app.feature.membership.domain.VerificationMethod
import com.safesphere.app.feature.membership.ui.MembershipLabels

/**
 * Phase N3 (A2): "Verify someone" (in person: the applicant's code, then the card) and an ID photo's review (admins,
 * [ARG_REF] + [ARG_BY_PHOTO]). FLAG_SECURE. The photo is decoded from memory into the view and dropped with the screen;
 * it is never written to the phone. The roll number is typed from the card, never shown by the app.
 */
class DecideFragment : ViewBindingFragment<FragmentStaffDecideBinding>(FragmentStaffDecideBinding::inflate) {

    private val viewModel by viewModels<DecisionViewModel> { DecisionViewModel.factory(appContainer) }
    private var shownPhoto: ByteArray? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val byPhoto = arguments?.getBoolean(ARG_BY_PHOTO) == true
        viewModel.start(arguments?.getString(ARG_REF), byPhoto)

        binding.lookupButton.setOnClickListener { viewModel.onLookup(binding.codeInput.text?.toString().orEmpty()) }
        binding.approveButton.setOnClickListener {
            viewModel.onApprove(binding.rollInput.text?.toString().orEmpty(), binding.nameInput.text?.toString().orEmpty())
        }
        binding.refuseButton.setOnClickListener { chooseRefusal() }
        binding.nextButton.setOnClickListener {
            if (viewModel.uiState.value.byPhoto) {
                findNavController().popBackStack()
            } else {
                clearInputs()
                viewModel.onNext()
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

    override fun onDestroyView() {
        binding.idPhoto.setImageDrawable(null)
        shownPhoto = null
        super.onDestroyView()
    }

    private fun render(state: DecideUiState) {
        binding.progress.isVisible = state.busy
        binding.codeBlock.isVisible = state.step == DecideStep.CODE && !state.byPhoto
        binding.codeLayout.error = if (state.codeProblem) getString(R.string.staff_code_problem) else null
        binding.lookupButton.isEnabled = !state.busy

        val detail = state.detail
        binding.detailBlock.isVisible = state.step == DecideStep.DETAILS && detail != null
        if (detail != null && state.step == DecideStep.DETAILS) {
            binding.personName.text = detail.name
            binding.personDetail.text = listOfNotNull(
                getString(R.string.membership_member_detail, detail.programmeLabel, detail.graduationYear),
                detail.phoneHint?.let { getString(R.string.staff_phone_hint, it) },
                getString(if (detail.method == VerificationMethod.ID_PHOTO) R.string.staff_by_photo else R.string.staff_in_person)
            ).joinToString("\n")
            binding.checkList.setText(if (state.byPhoto) R.string.staff_check_list_photo else R.string.staff_check_list)
        }
        val photo = state.photo
        if (photo !== shownPhoto) {
            shownPhoto = photo
            val bitmap = photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            binding.idPhoto.setImageBitmap(bitmap)
            binding.idPhoto.isVisible = bitmap != null
        }
        binding.rollLayout.error = if (state.rollProblem) getString(R.string.membership_roll_problem) else null
        binding.nameLayout.error = if (state.nameProblem) getString(R.string.staff_name_problem) else null
        binding.approveButton.isEnabled = !state.busy && !(state.byPhoto && state.photo == null)
        binding.refuseButton.isEnabled = !state.busy

        val decision = state.decision
        binding.doneBlock.isVisible = state.step == DecideStep.DONE && decision != null
        if (decision != null) {
            binding.doneText.text = when (decision.outcome) {
                DecisionOutcome.VERIFIED -> getString(R.string.staff_done_verified)
                DecisionOutcome.REFUSED -> getString(R.string.staff_done_refused, getString(MembershipLabels.refusal(decision.reason)))
            }
            binding.nextButton.setText(if (state.byPhoto) R.string.staff_back_to_photos else R.string.staff_next_person)
        }

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun chooseRefusal() {
        val reasons = RefusalReason.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.staff_refuse_title)
            .setItems(reasons.map { getString(StaffLabels.refusal(it)) }.toTypedArray()) { _, which ->
                viewModel.onRefuse(reasons[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clearInputs() {
        binding.codeInput.setText("")
        binding.rollInput.setText("")
        binding.nameInput.setText("")
    }

    companion object {
        const val ARG_REF = "ref"
        const val ARG_BY_PHOTO = "byPhoto"

        fun photoArgs(ref: String) = bundleOf(ARG_REF to ref, ARG_BY_PHOTO to true)
    }
}
