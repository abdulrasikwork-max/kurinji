package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.clearErrorOnTextChange
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showError
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentProfileSetupBinding
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.ProfileInputPolicy
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Phase 13A: create or edit one's profile (handle and bio; the name is the account's real name, programme and year come from the membership). */
class ProfileSetupFragment :
    ViewBindingFragment<FragmentProfileSetupBinding>(FragmentProfileSetupBinding::inflate) {

    private val viewModel by viewModels<ProfileSetupViewModel> { ProfileSetupViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listOf(binding.handleLayout, binding.bioLayout).forEach { it.clearErrorOnTextChange() }

        binding.dateOfBirthInput.setOnClickListener {
            DateOfBirthPicker.show(this, PICKER_TAG, viewModel.uiState.value.dateOfBirth, viewModel.today(),
                viewModel::onDateOfBirthChosen)
        }
        DateOfBirthPicker.reattach(this, PICKER_TAG, viewModel::onDateOfBirthChosen)
        binding.confirmAgeButton.setOnClickListener { viewModel.onConfirmAge() }
        binding.saveButton.setOnClickListener { viewModel.onSave(input()) }
        // Phase 17B: the first screen after a new account's phone was verified ("Choose your handle"). Saving or
        // skipping goes on to Today; this screen is the root there, so Back skips too.
        if (firstRun) {
            binding.skipButton.isVisible = true
            binding.skipButton.setOnClickListener { goOn() }
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { goOn() }
        }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun render(state: ProfileSetupUiState) {
        binding.progress.isVisible = state.isLoading || state.isSaving
        binding.ageCard.isVisible = state.needsAgeConfirmation
        binding.formBlock.isVisible = state.formReady && !state.needsAgeConfirmation
        binding.saveButton.isEnabled = !state.isSaving
        binding.confirmAgeButton.isEnabled = !state.isSaving

        binding.dateOfBirthLayout.showError(state.dateOfBirthError)
        val shown = state.dateOfBirth?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)).orEmpty()
        if (binding.dateOfBirthInput.text.toString() != shown) binding.dateOfBirthInput.setText(shown)

        state.prefill?.let {
            fill(it)
            viewModel.onPrefilled()
        }
        val wait = state.profile?.handleChangeAvailableAt
        binding.handleWait.isVisible = wait != null
        if (wait != null) {
            val date = wait.atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
            binding.handleWait.text = getString(R.string.social_handle_wait, date)
        }

        show(binding.handleLayout, state.problems.handle)
        show(binding.bioLayout, state.problems.bio)

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        if (state.ageJustConfirmed) {
            viewModel.onAgeConfirmedShown()
            Toast.makeText(requireContext(), R.string.social_age_confirmed, Toast.LENGTH_SHORT).show()
            // Phase 17A: opened by the app only to confirm the age; back to where the person was.
            if (arguments?.getBoolean(ARG_AGE_ONLY) == true) findNavController().popBackStack()
        }
        if (state.saved) {
            viewModel.onSavedShown()
            Toast.makeText(requireContext(), R.string.social_profile_saved, Toast.LENGTH_SHORT).show()
            if (firstRun) goOn()
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private val firstRun: Boolean get() = arguments?.getBoolean(ARG_FIRST_RUN) == true

    private fun goOn() {
        findNavController().navigate(R.id.action_profile_setup_to_start)
    }

    private fun fill(profile: OwnProfile) {
        binding.handleInput.setText(profile.handle)
        binding.bioInput.setText(profile.bio.orEmpty())
    }

    private fun input() = ProfileInput(
        handle = binding.handleInput.text.toString(),
        bio = binding.bioInput.text.toString()
    )

    private fun show(layout: com.google.android.material.textfield.TextInputLayout, problem: ProfileInputPolicy.Problem?) {
        layout.error = problem?.let { getString(SocialLabels.of(it)) }
    }

    companion object {
        private const val PICKER_TAG = "profile_date_of_birth"

        /** Phase 17A: true when the app opened this screen only to confirm the age ([com.safesphere.app.core.network.AgeGate]). */
        const val ARG_AGE_ONLY = "ageOnly"

        /** Phase 17B: true right after a new account's phone was verified (then Today follows). */
        const val ARG_FIRST_RUN = "firstRun"
    }
}
