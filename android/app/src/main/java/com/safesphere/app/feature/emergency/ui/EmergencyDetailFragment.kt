package com.safesphere.app.feature.emergency.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentEmergencyDetailBinding
import com.safesphere.app.feature.circlealert.domain.EmergencyContext
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.safesphere.app.feature.evidence.ui.EvidenceVaultFragment

/**
 * Phase 4D: what happened during one emergency, from its Emergency Context Packet (OWNER projection, assembled by the
 * server on demand): times, the walk that led to it, the last saved location, the escalation ladder and every
 * message to the Safety Circle with its honest status. Only the owner can open it.
 */
class EmergencyDetailFragment : ViewBindingFragment<FragmentEmergencyDetailBinding>(FragmentEmergencyDetailBinding::inflate) {

    private val viewModel by viewModels<EmergencyDetailViewModel> {
        EmergencyDetailViewModel.factory(appContainer, requireArguments().getLong(ARG_EMERGENCY_ID))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.detailRetry.setOnClickListener { viewModel.load() }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(viewModel.sessionExpired) { expired ->
            if (expired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
    }

    private fun render(state: EmergencyDetailUiState) {
        binding.detailProgress.isVisible = state is EmergencyDetailUiState.Loading
        binding.detailError.isVisible = state is EmergencyDetailUiState.Failed
        binding.detailRetry.isVisible = state is EmergencyDetailUiState.Failed
        binding.detailContent.isVisible = state is EmergencyDetailUiState.Loaded
        when (state) {
            is EmergencyDetailUiState.Failed -> binding.detailError.setMessage(state.failure.toMessage(requireContext()))
            is EmergencyDetailUiState.Loaded -> renderContext(state.context)
            EmergencyDetailUiState.Loading -> Unit
        }
    }

    private fun renderContext(context: EmergencyContext) {
        binding.detailSummary.text = buildList {
            add(getString(R.string.emergency_started_at, EmergencyTimeFormatter.format(context.startedAt)))
            context.endedAt?.let { add(getString(R.string.emergency_ended_at, EmergencyTimeFormatter.format(it))) }
            add(getString(R.string.detail_status, statusLabel(context.status)))
            add(getString(R.string.detail_highest_level, context.highestLevel))
            context.walkDestinationLabel?.let { add(getString(R.string.detail_walk_destination, it)) }
        }.joinToString("\n")

        val link = context.latestLocationLink
        val locationAt = context.latestLocationAt
        binding.detailLocation.text = if (link != null && locationAt != null) {
            val count = context.locationCount.toInt()
            resources.getQuantityString(R.plurals.detail_location, count, EmergencyTimeFormatter.format(locationAt), count)
        } else {
            getString(R.string.detail_no_location)
        }
        binding.detailOpenMap.isVisible = link != null
        binding.detailOpenMap.setOnClickListener { link?.let(::openLink) }

        binding.detailEvidence.text = if (context.evidenceItems == 0L) {
            getString(R.string.detail_no_evidence)
        } else {
            resources.getQuantityString(R.plurals.detail_evidence, context.evidenceItems.toInt(),
                context.evidenceItems.toInt(), context.evidenceSealed.toInt())
        }
        binding.detailOpenVault.setOnClickListener {
            findNavController().navigate(R.id.evidenceVaultFragment, bundleOf(EvidenceVaultFragment.ARG_EMERGENCY_ID to context.emergencyId))
        }

        val entries = detailEntriesFor(context)
        binding.detailTimeline.text = if (entries.isEmpty()) {
            getString(R.string.detail_nothing_recorded)
        } else {
            entries.joinToString("\n") { entry ->
                val time = EmergencyTimeFormatter.formatTime(entry.at)
                when (entry) {
                    is DetailEntry.Escalation -> getString(
                        R.string.detail_escalation_line, time, entry.item.level, getString(escalationReasonLabel(entry.item.reason))
                    )
                    is DetailEntry.Alert -> getString(
                        R.string.detail_alert_line, time,
                        entry.item.contactName ?: getString(R.string.detail_unknown_contact),
                        getString(alertKindLabel(entry.item.kind)),
                        getString(alertStatusLabel(entry.item.status, entry.item.channel))
                    )
                }
            }
        }
    }

    private fun statusLabel(status: String): String = getString(
        when (status) {
            "ACTIVE" -> R.string.emergency_status_active
            "RESOLVED" -> R.string.emergency_status_resolved
            else -> R.string.emergency_status_cancelled
        }
    )

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // No browser or maps app: the location line above still says when it was saved.
        }
    }

    companion object {
        const val ARG_EMERGENCY_ID = "emergencyId"
    }
}
