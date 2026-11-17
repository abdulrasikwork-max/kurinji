package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.core.network.needsMembership
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentPersonProfileBinding
import com.safesphere.app.feature.social.domain.PersonProfile
import com.safesphere.app.feature.social.domain.Relationship
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.moments.ui.MomentFragment
import com.safesphere.app.feature.moments.ui.MomentListAdapter
import com.safesphere.app.feature.moments.ui.ProfileMomentRows
import com.safesphere.app.feature.moments.ui.momentImages
import com.safesphere.app.feature.moments.ui.secure

/**
 * Phase 13A: someone's profile. One main button follows the relationship: Connect, Accept (asking back connects),
 * "Request sent", or Edit profile on one's own. Remove connection, mute and block ask nothing of the other person and
 * tell them nothing. N4: their moments the viewer may see are listed under the profile, and screenshots are blocked
 * while someone else's profile is on the screen (D12; never on one's own).
 */
class PersonProfileFragment :
    ViewBindingFragment<FragmentPersonProfileBinding>(FragmentPersonProfileBinding::inflate) {

    private val viewModel by viewModels<PersonProfileViewModel> { PersonProfileViewModel.factory(appContainer) }

    private lateinit var momentAdapter: MomentListAdapter

    /** Covered moments the reader chose to read. */
    private val opened = HashSet<String>()
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.getVerifiedButton.setOnClickListener { findNavController().navigate(R.id.membershipFragment) }
        momentAdapter = MomentListAdapter(momentImages(), onOpen = { moment ->
            findNavController().navigate(R.id.momentFragment, bundleOf(MomentFragment.ARG_ID to moment.id))
        }, onUncover = { moment ->
            opened += moment.id
            render(viewModel.uiState.value)
        })
        binding.removeButton.setOnClickListener { confirmRemove() }
        binding.muteButton.setOnClickListener { viewModel.onMute() }
        binding.blockButton.setOnClickListener { confirmBlock() }
        binding.momentList.adapter = momentAdapter
        binding.reportPersonButton.setOnClickListener {
            val id = viewModel.uiState.value.person?.publicId ?: return@setOnClickListener
            ReportDialogs.show(this, ReportTarget.PERSON) { reason, note -> reports.report(ReportTarget.PERSON, id, reason, note) }
        }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(reports.uiState) { state ->
            if (state.message == ReportMessage.REPORT_SENT) Toast.makeText(requireContext(), R.string.report_sent,
                Toast.LENGTH_LONG).show()
            if (state.message != null) reports.onMessageShown()
            state.failure?.let {
                Toast.makeText(requireContext(), it.toMessage(requireContext()), Toast.LENGTH_LONG).show()
                reports.onFailureShown()
            }
            if (state.sessionExpired) {
                reports.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applySecure()
    }

    override fun onPause() {
        super.onPause()
        secure(false)
    }

    /** D12: someone else's profile is protected; until the profile is known it counts as someone else's. */
    private fun applySecure() {
        if (isResumed) secure(viewModel.uiState.value.person?.relationship != Relationship.SELF)
    }

    private fun render(state: PersonProfileUiState) {
        if (view == null) return
        applySecure()
        binding.progress.isVisible = state.isLoading || state.isBusy
        binding.notFound.isVisible = state.notFound
        val person = state.person
        binding.contentBlock.isVisible = person != null && !state.notFound
        if (person != null) show(person, state.isBusy)
        momentAdapter.submitList(if (person == null) emptyList()
            else ProfileMomentRows.of(state.live, state.kept, opened))

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        binding.getVerifiedButton.isVisible = failure.needsMembership()
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        state.message?.let {
            viewModel.onMessageShown()
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
        }
        if (state.closed) {
            viewModel.onClosedHandled()
            findNavController().navigateUp()
            return
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun show(person: PersonProfile, busy: Boolean) {
        binding.displayName.text = person.displayName
        personImages().avatar(binding.avatar, person.displayName, person.handle, person.publicId, person.photo)
        binding.handle.text = getString(R.string.social_handle_line, person.handle)
        binding.bio.text = person.bio.orEmpty()
        binding.bio.isVisible = !person.bio.isNullOrBlank()
        val community = CommunityLine.of(person.programme, person.graduationYear)
        binding.community.text = community.orEmpty()
        binding.community.isVisible = community != null

        val self = person.relationship == Relationship.SELF
        binding.relationship.text = when (person.relationship) {
            Relationship.SELF -> getString(R.string.social_relationship_self)
            Relationship.REQUESTED -> getString(R.string.social_relationship_requested)
            Relationship.REQUEST_RECEIVED -> getString(R.string.social_relationship_received)
            Relationship.CONNECTED -> getString(R.string.social_relationship_connected)
            Relationship.NONE -> ""
        }
        binding.relationship.isVisible = person.relationship != Relationship.NONE

        binding.primaryButton.isVisible = person.relationship != Relationship.CONNECTED
        binding.primaryButton.isEnabled = !busy && person.relationship != Relationship.REQUESTED
        when (person.relationship) {
            Relationship.SELF -> {
                binding.primaryButton.setText(R.string.social_edit_profile)
                binding.primaryButton.setOnClickListener { findNavController().navigate(R.id.profileSetupFragment) }
            }
            Relationship.REQUEST_RECEIVED -> {
                binding.primaryButton.setText(R.string.social_accept)
                binding.primaryButton.setOnClickListener { viewModel.onConnect() }
            }
            Relationship.REQUESTED -> binding.primaryButton.setText(R.string.social_relationship_requested)
            else -> {
                binding.primaryButton.setText(R.string.social_connect)
                binding.primaryButton.setOnClickListener { viewModel.onConnect() }
            }
        }
        binding.removeButton.isVisible = person.relationship == Relationship.CONNECTED
        binding.removeButton.isEnabled = !busy
        binding.moreActions.isVisible = !self
        binding.reportPersonButton.isVisible = !self
        binding.muteButton.isEnabled = !busy
        binding.blockButton.isEnabled = !busy
    }

    private fun confirmRemove() {
        val person = viewModel.uiState.value.person ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.social_remove_connection_title)
            .setMessage(getString(R.string.social_remove_connection_message, person.displayName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ -> viewModel.onRemoveConnectionConfirmed() }
            .show()
    }

    private fun confirmBlock() {
        val person = viewModel.uiState.value.person ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.social_block_title, person.displayName))
            .setMessage(R.string.social_block_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.social_block) { _, _ -> viewModel.onBlockConfirmed() }
            .show()
    }
}
