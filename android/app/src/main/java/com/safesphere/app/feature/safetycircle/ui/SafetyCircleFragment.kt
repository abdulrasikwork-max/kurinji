package com.safesphere.app.feature.safetycircle.ui

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.safesphere.app.databinding.FragmentSafetyCircleBinding
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/**
 * Safety Circle: the list of trusted people. The screen only shows state and forwards taps; loading,
 * saving and deleting happen in the ViewModel and the repository.
 */
class SafetyCircleFragment :
    ViewBindingFragment<FragmentSafetyCircleBinding>(FragmentSafetyCircleBinding::inflate) {

    private val viewModel by viewModels<SafetyCircleViewModel> { SafetyCircleViewModel.factory(appContainer) }

    private val autoSmsViewModel by viewModels<AutoSmsViewModel> { AutoSmsViewModel.factory(appContainer) }

    /** Phase 4D: SEND_SMS is asked only here, right after the person switched automatic alerts on. */
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            autoSmsViewModel.onPermissionResult(granted)
            if (!granted) Toast.makeText(requireContext(), R.string.auto_sms_permission_denied, Toast.LENGTH_LONG).show()
        }

    private val adapter = ContactAdapter(
        onEdit = ::openForm,
        onDelete = ::confirmDelete,
        onEnabledChanged = { contact, enabled -> viewModel.onEnabledChanged(contact, enabled) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.contactList.adapter = adapter
        binding.addContactButton.setOnClickListener { openForm(null) }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(autoSmsViewModel.ui, ::renderAutoSms)
        binding.autoSmsSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener // only the person's own taps
            if (checked) onAutoSmsSwitchedOn() else autoSmsViewModel.onSwitchOff()
        }
    }

    private fun renderAutoSms(ui: AutoSmsUi) {
        if (binding.autoSmsSwitch.isChecked != ui.enabled) binding.autoSmsSwitch.isChecked = ui.enabled
        binding.autoSmsStatus.text = if (ui.status == R.string.auto_sms_status_on) {
            getString(R.string.auto_sms_status_on, ui.recipientCount)
        } else {
            getString(ui.status)
        }
        binding.autoSmsPreview.text = ui.preview
    }

    private fun onAutoSmsSwitchedOn() {
        when (autoSmsViewModel.onSwitchOn()) {
            AutoSmsEnableAction.ENABLE -> Unit
            AutoSmsEnableAction.NOT_SUPPORTED -> binding.autoSmsSwitch.isChecked = false
            AutoSmsEnableAction.ASK_PERMISSION -> {
                binding.autoSmsSwitch.isChecked = false // on only once the permission is granted
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.auto_sms_permission_title)
                    .setMessage(R.string.auto_sms_permission_rationale)
                    .setNegativeButton(R.string.action_dismiss, null)
                    .setPositiveButton(R.string.action_continue) { _, _ ->
                        smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                    }
                    .show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        autoSmsViewModel.onScreenVisible() // the permission may have changed in Settings
        // Coming back from the Add / Edit form: show the latest data. (The first load happens in the ViewModel.)
        if (viewModel.uiState.value.hasLoaded) viewModel.load()
    }

    private fun render(state: SafetyCircleUiState) {
        adapter.submitList(state.contacts)

        binding.progress.isVisible = state.isLoading
        binding.contactList.isVisible = state.contacts.isNotEmpty()
        binding.emptyState.isVisible = state.hasLoaded && state.contacts.isEmpty() && state.failure == null

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun openForm(contact: SafetyContact?) {
        val args = bundleOf(ContactFormFragment.ARG_CONTACT_ID to (contact?.id ?: ContactFormFragment.NEW_CONTACT))
        findNavController().navigate(R.id.contactFormFragment, args)
    }

    private fun confirmDelete(contact: SafetyContact) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_contact_title)
            .setMessage(getString(R.string.delete_contact_message, contact.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ -> viewModel.onDeleteConfirmed(contact) }
            .show()
    }
}
