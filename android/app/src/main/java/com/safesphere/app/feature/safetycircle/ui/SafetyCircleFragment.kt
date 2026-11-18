package com.safesphere.app.feature.safetycircle.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.safesphere.app.feature.circlealert.domain.AlertChoice
import com.safesphere.app.feature.circlealert.domain.ServerAlertStatus
import com.safesphere.app.feature.circlealert.domain.SkipCause
import com.safesphere.app.feature.emergency.ui.EmergencyTimeFormatter
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/**
 * Safety Circle: the list of trusted people. The screen only shows state and forwards taps; loading,
 * saving and deleting happen in the ViewModel and the repository.
 *
 * N6 (D20): the card above the list is the person's one explicit choice whether IRL's server texts these people in an
 * SOS, with the exact text first; "Tell them in advance" (a share-sheet note: no location, no link) and "Test my
 * alerts" (a text to the person's own number). No SMS permission exists any more.
 */
class SafetyCircleFragment :
    ViewBindingFragment<FragmentSafetyCircleBinding>(FragmentSafetyCircleBinding::inflate) {

    private val viewModel by viewModels<SafetyCircleViewModel> { SafetyCircleViewModel.factory(appContainer) }

    private val alertsViewModel by viewModels<SafetyAlertsViewModel> { SafetyAlertsViewModel.factory(appContainer) }

    private val adapter = ContactAdapter(
        onEdit = ::openForm,
        onDelete = ::confirmDelete,
        onEnabledChanged = { contact, enabled -> viewModel.onEnabledChanged(contact, enabled) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.contactList.adapter = adapter
        binding.addContactButton.setOnClickListener { openForm(null) }
        binding.alertsOnButton.setOnClickListener { alertsViewModel.choose(true) }
        binding.alertsOffButton.setOnClickListener { alertsViewModel.choose(false) }
        binding.alertsTestButton.setOnClickListener { alertsViewModel.test() }
        binding.alertsTellButton.setOnClickListener { tellInAdvance() }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(alertsViewModel.ui, ::renderAlerts)
    }

    override fun onStart() {
        super.onStart()
        // Coming back from the Add / Edit form: show the latest data. (The first load happens in the ViewModel.)
        if (viewModel.uiState.value.hasLoaded) viewModel.load()
        alertsViewModel.load()
    }

    private fun renderAlerts(ui: SafetyAlertsUi) {
        val settings = ui.settings
        binding.alertsPreview.text = settings?.preview ?: ""
        binding.alertsPreview.isVisible = settings?.preview != null
        binding.alertsPreviewLabel.isVisible = settings?.preview != null
        binding.alertsStatus.setText(when {
            settings == null && ui.failed -> R.string.alerts_status_failed
            settings == null -> R.string.alerts_status_unset
            settings.choice == AlertChoice.ON -> R.string.alerts_status_on
            settings.choice == AlertChoice.OFF -> R.string.alerts_status_off
            else -> R.string.alerts_status_unset
        })
        val notTexted = settings?.contacts.orEmpty().filterNot { it.automatic }
        binding.alertsContacts.isVisible = notTexted.isNotEmpty()
        binding.alertsContacts.text = notTexted.joinToString("\n") {
            getString(R.string.alerts_contact_line, it.name, getString(reasonText(it.reason)))
        }
        val choice = settings?.choice
        binding.alertsOnButton.isVisible = settings != null && choice != AlertChoice.ON
        binding.alertsOnButton.setText(if (choice == AlertChoice.OFF) R.string.alerts_turn_on_again else R.string.alerts_turn_on)
        binding.alertsOffButton.isVisible = settings != null && choice != AlertChoice.OFF
        binding.alertsOffButton.setText(if (choice == AlertChoice.ON) R.string.alerts_turn_off_now else R.string.alerts_turn_off)
        binding.alertsOnButton.isEnabled = !ui.busy
        binding.alertsOffButton.isEnabled = !ui.busy
        binding.alertsTellButton.isVisible = settings?.contacts.orEmpty().isNotEmpty()
        binding.alertsTestButton.isVisible = settings?.testAvailable == true
        binding.alertsTestButton.isEnabled = !ui.busy

        val test = settings?.lastTest
        val testLine: String? = when {
            ui.testLimit -> getString(R.string.alerts_test_limit)
            test == null -> null
            test.status == ServerAlertStatus.QUEUED -> getString(R.string.alerts_test_queued)
            test.status == ServerAlertStatus.SENT || test.status == ServerAlertStatus.DELIVERED ->
                getString(R.string.alerts_test_sent, test.at?.let { EmergencyTimeFormatter.formatTime(it) } ?: "")
            test.status == ServerAlertStatus.FAILED || test.status == ServerAlertStatus.SKIPPED ->
                getString(R.string.alerts_test_failed)
            else -> getString(R.string.alerts_test_unknown)
        }
        binding.alertsTestStatus.isVisible = testLine != null
        binding.alertsTestStatus.text = testLine ?: ""
    }

    private fun reasonText(reason: SkipCause?): Int = when (reason) {
        SkipCause.NOT_INDIAN_MOBILE -> R.string.alerts_reason_not_indian_mobile
        SkipCause.NOT_IN_FIRST_FIVE -> R.string.alerts_reason_not_in_first_five
        SkipCause.DUPLICATE_NUMBER -> R.string.alerts_reason_duplicate
        else -> R.string.alerts_reason_other
    }

    /** A plain note through the share sheet: the person picks the app and the people. No location, no link. */
    private fun tellInAdvance() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.alerts_tell_text))
        startActivity(Intent.createChooser(send, getString(R.string.alerts_tell_in_advance)))
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
