package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.clearErrorOnTextChange
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showError
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentProfileSetupBinding
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Language
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.ProfileInputPolicy
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Phase 13A: create or edit one's profile (handle, name, bio, city, languages, interests). */
class ProfileSetupFragment :
    ViewBindingFragment<FragmentProfileSetupBinding>(FragmentProfileSetupBinding::inflate) {

    private val viewModel by viewModels<ProfileSetupViewModel> { ProfileSetupViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addChips(binding.languageChips, Language.entries.map { it.name to SocialLabels.of(it) })
        addChips(binding.interestChips, Interest.entries.map { it.name to SocialLabels.of(it) })
        listOf(binding.handleLayout, binding.displayNameLayout, binding.bioLayout, binding.cityLayout)
            .forEach { it.clearErrorOnTextChange() }

        binding.dateOfBirthInput.setOnClickListener {
            DateOfBirthPicker.show(this, PICKER_TAG, viewModel.uiState.value.dateOfBirth, viewModel.today(),
                viewModel::onDateOfBirthChosen)
        }
        DateOfBirthPicker.reattach(this, PICKER_TAG, viewModel::onDateOfBirthChosen)
        binding.confirmAgeButton.setOnClickListener { viewModel.onConfirmAge() }
        binding.saveButton.setOnClickListener { viewModel.onSave(input()) }

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
        show(binding.displayNameLayout, state.problems.displayName)
        show(binding.bioLayout, state.problems.bio)
        show(binding.cityLayout, state.problems.city)

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
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun fill(profile: OwnProfile) {
        binding.handleInput.setText(profile.handle)
        binding.displayNameInput.setText(profile.displayName)
        binding.bioInput.setText(profile.bio.orEmpty())
        binding.cityInput.setText(profile.city.orEmpty())
        check(binding.languageChips, profile.languages.map { it.name }.toSet())
        check(binding.interestChips, profile.interests.map { it.name }.toSet())
    }

    private fun input() = ProfileInput(
        handle = binding.handleInput.text.toString(),
        displayName = binding.displayNameInput.text.toString(),
        bio = binding.bioInput.text.toString(),
        languages = checked(binding.languageChips).mapNotNull { name -> Language.entries.firstOrNull { it.name == name } }
            .toSet(),
        interests = checked(binding.interestChips).mapNotNull { name -> Interest.entries.firstOrNull { it.name == name } }
            .toSet(),
        city = binding.cityInput.text.toString()
    )

    private fun show(layout: com.google.android.material.textfield.TextInputLayout, problem: ProfileInputPolicy.Problem?) {
        layout.error = problem?.let { getString(SocialLabels.of(it)) }
    }

    private fun addChips(group: ChipGroup, items: List<Pair<String, Int>>) {
        if (group.childCount > 0) return
        for ((tag, label) in items) {
            val chip = Chip(requireContext()).apply {
                text = getString(label)
                this.tag = tag
                isCheckable = true
            }
            group.addView(chip)
        }
    }

    private fun check(group: ChipGroup, tags: Set<String>) {
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as Chip
            chip.isChecked = chip.tag in tags
        }
    }

    private fun checked(group: ChipGroup): List<String> =
        (0 until group.childCount).map { group.getChildAt(it) as Chip }.filter { it.isChecked }.map { it.tag as String }

    companion object {
        private const val PICKER_TAG = "profile_date_of_birth"

        /** Phase 17A: true when the app opened this screen only to confirm the age ([com.safesphere.app.core.network.AgeGate]). */
        const val ARG_AGE_ONLY = "ageOnly"
    }
}
