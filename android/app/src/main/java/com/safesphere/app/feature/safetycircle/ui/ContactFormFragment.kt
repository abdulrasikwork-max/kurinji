package com.safesphere.app.feature.safetycircle.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
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
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentContactFormBinding
import com.safesphere.app.feature.safetycircle.domain.ContactFormValidator
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/** Add or edit one trusted contact (edit when a contact id is passed in the navigation arguments). */
class ContactFormFragment :
    ViewBindingFragment<FragmentContactFormBinding>(FragmentContactFormBinding::inflate) {

    private val contactId: Long?
        get() = arguments?.getLong(ARG_CONTACT_ID, NEW_CONTACT)?.takeIf { it != NEW_CONTACT }

    private val viewModel by viewModels<ContactFormViewModel> {
        ContactFormViewModel.factory(appContainer, contactId)
    }

    private val priorityLabels: List<String> by lazy {
        listOf(
            R.string.priority_1, R.string.priority_2, R.string.priority_3, R.string.priority_4, R.string.priority_5
        ).map { getString(it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.formTitle.setText(if (viewModel.isEditing) R.string.contact_form_edit_title else R.string.contact_form_add_title)
        binding.deleteButton.isVisible = viewModel.isEditing

        setUpDropdowns(savedInstanceState)
        listOf(binding.nameLayout, binding.phoneLayout, binding.emailLayout).forEach { it.clearErrorOnTextChange() }

        // A disabled contact cannot be primary, so the primary switch follows the enabled switch.
        binding.enabledSwitch.setOnCheckedChangeListener { _, enabled -> updatePrimarySwitch(enabled) }
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun setUpDropdowns(savedInstanceState: Bundle?) {
        val relationshipLabels = Relationship.entries.map { getString(it.labelRes) }
        binding.relationshipInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, relationshipLabels)
        )
        binding.priorityInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, priorityLabels)
        )
        binding.relationshipInput.setOnItemClickListener { _, _, _, _ -> binding.relationshipLayout.error = null }
        binding.priorityInput.setOnItemClickListener { _, _, _, _ -> binding.priorityLayout.error = null }

        // New contacts start at the middle priority. (Skipped after rotation: the text is restored by Android.)
        if (savedInstanceState == null && !viewModel.isEditing) {
            binding.priorityInput.setText(priorityLabels[ContactFormValidator.DEFAULT_PRIORITY - 1], false)
        }
    }

    private fun render(state: ContactFormUiState) {
        val contact = state.loadedContact
        if (contact != null && !state.formPopulated) {
            fillForm(contact)
            viewModel.onFormPopulated()
        }

        binding.nameLayout.showError(state.errors.name)
        binding.phoneLayout.showError(state.errors.phone)
        binding.emailLayout.showError(state.errors.email)
        binding.relationshipLayout.showError(state.errors.relationship)
        binding.priorityLayout.showError(state.errors.priority)

        binding.progress.isVisible = state.isSaving || state.isLoadingContact
        binding.saveButton.isEnabled = !state.isSaving && !state.isLoadingContact
        binding.deleteButton.isEnabled = !state.isSaving && !state.isLoadingContact

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        } else if (state.finished && findNavController().currentDestination?.id == R.id.contactFormFragment) {
            findNavController().navigateUp()
        }
    }

    private fun fillForm(contact: SafetyContact) {
        binding.nameInput.setText(contact.name)
        binding.phoneInput.setText(contact.phoneNumber)
        binding.emailInput.setText(contact.email.orEmpty())
        binding.relationshipInput.setText(getString(contact.relationship.labelRes), false)
        binding.priorityInput.setText(priorityLabels[contact.priority - 1], false)
        binding.enabledSwitch.isChecked = contact.isEnabled
        binding.primarySwitch.isChecked = contact.isPrimary
        updatePrimarySwitch(contact.isEnabled)
    }

    private fun updatePrimarySwitch(contactEnabled: Boolean) {
        binding.primarySwitch.isEnabled = contactEnabled
        if (!contactEnabled) binding.primarySwitch.isChecked = false
    }

    private fun save() {
        val relationshipText = binding.relationshipInput.text.toString()
        val relationship = Relationship.entries.firstOrNull { getString(it.labelRes) == relationshipText }
        val priorityIndex = priorityLabels.indexOf(binding.priorityInput.text.toString())

        viewModel.onSaveClicked(
            name = binding.nameInput.text.toString(),
            phone = binding.phoneInput.text.toString(),
            email = binding.emailInput.text.toString(),
            relationship = relationship,
            priority = if (priorityIndex >= 0) priorityIndex + 1 else null,
            isPrimary = binding.primarySwitch.isChecked,
            isEnabled = binding.enabledSwitch.isChecked
        )
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_contact_title)
            .setMessage(getString(R.string.delete_contact_message, binding.nameInput.text.toString().ifBlank { getString(R.string.this_person) }))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ -> viewModel.onDeleteConfirmed() }
            .show()
    }

    companion object {
        const val ARG_CONTACT_ID = "contactId"
        const val NEW_CONTACT = -1L
    }
}
