package com.safesphere.app.feature.social.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentConnectionsBinding
import com.safesphere.app.feature.social.domain.InviteItem
import com.safesphere.app.feature.social.domain.NewInvite
import com.safesphere.app.feature.social.domain.PersonSummary
import com.safesphere.app.feature.social.domain.RequestItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase 13A: the person's own connections screen (requests, connections, invite codes, finding someone by exact
 * handle). The screen shows state and forwards taps; the ViewModel talks to the server.
 */
class ConnectionsFragment : ViewBindingFragment<FragmentConnectionsBinding>(FragmentConnectionsBinding::inflate) {

    private val viewModel by viewModels<ConnectionsViewModel> { ConnectionsViewModel.factory(appContainer) }

    private val incomingAdapter = PersonRowAdapter()
    private val outgoingAdapter = PersonRowAdapter()
    private val connectionAdapter = PersonRowAdapter()
    private val inviteAdapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.incomingList.adapter = incomingAdapter
        binding.outgoingList.adapter = outgoingAdapter
        binding.connectionList.adapter = connectionAdapter
        binding.inviteList.adapter = inviteAdapter

        binding.makeProfileButton.setOnClickListener { findNavController().navigate(R.id.profileSetupFragment) }
        binding.blockedMutedButton.setOnClickListener { findNavController().navigate(R.id.blockedMutedFragment) }
        binding.findButton.setOnClickListener { viewModel.onFind(binding.findInput.text.toString()) }
        binding.findInput.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_SEARCH).also { if (it) viewModel.onFind(binding.findInput.text.toString()) }
        }
        binding.findInput.doAfterTextChanged { viewModel.onHandleTyped() }
        binding.createInviteButton.setOnClickListener { viewModel.onCreateInvite() }
        binding.redeemButton.setOnClickListener { viewModel.onRedeem(binding.redeemInput.text.toString()) }
        binding.redeemInput.doAfterTextChanged { viewModel.onCodeTyped() }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        // Coming back from a profile or the profile form: show what the server holds now.
        if (viewModel.uiState.value.hasLoaded) viewModel.load()
    }

    private fun render(state: ConnectionsUiState) {
        binding.progress.isVisible = state.isLoading
        val me = state.me
        binding.noProfileBlock.isVisible = state.hasLoaded && me == null && state.failure == null
        binding.contentBlock.isVisible = me != null

        incomingAdapter.submitList(state.incoming.map(::incomingRow))
        outgoingAdapter.submitList(state.outgoing.map(::outgoingRow))
        connectionAdapter.submitList(state.connections.map(::connectionRow))
        inviteAdapter.submitList(state.invites.map(::inviteRow))
        binding.incomingEmpty.isVisible = state.hasLoaded && state.incoming.isEmpty()
        binding.outgoingEmpty.isVisible = state.hasLoaded && state.outgoing.isEmpty()
        binding.connectionsEmpty.isVisible = state.hasLoaded && state.connections.isEmpty()

        binding.findLayout.error = if (state.handleInvalid) getString(R.string.social_find_invalid) else null
        binding.redeemLayout.error = if (state.codeInvalid) getString(R.string.social_code_invalid) else null

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        state.newInvite?.let {
            viewModel.onNewInviteShown()
            showNewInvite(it)
        }
        state.openHandle?.let {
            viewModel.onOpenHandled()
            findNavController().navigate(R.id.personProfileFragment, bundleOf(PersonProfileViewModel.ARG_HANDLE to it))
        }
        state.connectedWith?.let {
            viewModel.onConnectedShown()
            binding.redeemInput.text = null
            Toast.makeText(requireContext(), getString(R.string.social_connected_with, it.displayName), Toast.LENGTH_LONG)
                .show()
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun incomingRow(item: RequestItem) = personRow(item.person, item.requestId).copy(
        primary = RowAction(getString(R.string.social_accept),
            getString(R.string.social_accept_cd, item.person.displayName)) { viewModel.onAccept(item) },
        secondary = RowAction(getString(R.string.social_decline),
            getString(R.string.social_decline_cd, item.person.displayName)) { viewModel.onDecline(item) }
    )

    private fun outgoingRow(item: RequestItem) = personRow(item.person, item.requestId).copy(
        secondary = RowAction(getString(R.string.social_cancel_request),
            getString(R.string.social_cancel_request_cd, item.person.displayName)) { viewModel.onCancel(item) }
    )

    private fun connectionRow(person: PersonSummary) = personRow(person, person.publicId)

    private fun personRow(person: PersonSummary, key: String) = PersonRow(
        key = key,
        title = person.displayName,
        subtitle = getString(R.string.social_handle_line, person.handle),
        onOpen = { openPerson(person) },
        openDescription = getString(R.string.social_open_profile_cd, person.displayName)
    )

    private fun inviteRow(invite: InviteItem): PersonRow {
        val until = invite.expiresAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
        return PersonRow(
            key = invite.id,
            title = getString(R.string.social_invite_active, until),
            subtitle = "",
            secondary = RowAction(getString(R.string.social_revoke), getString(R.string.social_revoke_cd, until)) {
                viewModel.onRevokeInvite(invite)
            }
        )
    }

    private fun openPerson(person: PersonSummary) {
        findNavController().navigate(
            R.id.personProfileFragment, bundleOf(PersonProfileViewModel.ARG_PROFILE_ID to person.publicId)
        )
    }

    /** The code is shown once (the server keeps only its hash), in groups of five so it is easy to read out. */
    private fun showNewInvite(invite: NewInvite) {
        val grouped = invite.code.chunked(5).joinToString(" ")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.social_new_invite_title)
            .setMessage(getString(R.string.social_new_invite_message) + "\n\n" + grouped)
            .setPositiveButton(R.string.social_share_code) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, getString(R.string.social_invite_share_text, grouped))
                }
                startActivity(Intent.createChooser(send, getString(R.string.social_share_code)))
            }
            .setNeutralButton(R.string.social_copy_code) { _, _ ->
                requireContext().getSystemService<ClipboardManager>()
                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.social_new_invite_title), grouped))
                Toast.makeText(requireContext(), R.string.social_code_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_done, null)
            .show()
    }
}
