package com.safesphere.app.feature.moments.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Toast
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
import com.safesphere.app.databinding.FragmentMomentBinding
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase N4: a moment on its own page (a reading screen: the bottom bar stays). The author sees when it leaves Today
 * and can keep, un-keep or delete it; everyone else can open the author's profile. Screenshots are blocked (D12).
 */
class MomentFragment : ViewBindingFragment<FragmentMomentBinding>(FragmentMomentBinding::inflate) {

    private val viewModel by viewModels<MomentViewModel> { MomentViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.retryButton.setOnClickListener { viewModel.retry() }
        binding.keepButton.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.canUnkeep) viewModel.onUnkeep() else viewModel.onKeep()
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.momentCard.content.setOnClickListener { viewModel.onUncover() }
        collectWhenStarted(viewModel.uiState, ::render)
        viewModel.load(requireArguments().getString(ARG_ID).orEmpty())
    }

    override fun onResume() {
        super.onResume()
        secure(true)
    }

    override fun onPause() {
        super.onPause()
        secure(false)
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.moment_delete_title)
            .setMessage(R.string.moment_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.moment_action_delete) { _, _ -> viewModel.onDelete() }
            .show()
    }

    private fun render(state: MomentUiState) {
        if (view == null) return
        if (state.removed) {
            val nav = findNavController()
            state.moment?.let { nav.previousBackStackEntry?.savedStateHandle?.set(RESULT_REMOVED, it.id) }
            nav.popBackStack()
            return
        }
        val moment = state.moment
        binding.progress.isVisible = state.loading || state.busy
        binding.goneBlock.isVisible = state.gone
        binding.momentCard.root.isVisible = moment != null
        binding.ownBlock.isVisible = moment != null && moment.mine
        binding.authorButton.isVisible = false
        if (moment != null) {
            MomentCardBinder.bind(binding.momentCard, moment, state.open, momentImages())
            binding.momentCard.content.isClickable = MomentCardBinder.covered(moment, state.open)
            if (moment.mine) renderOwn(state, moment) else renderAuthor(moment)
        }

        val failure = state.failure
        if (failure != null && moment != null) {
            // Our own words for a refused keep ("You can keep 6 moments…") or a lost connection, once.
            Toast.makeText(requireContext(), failure.toMessage(requireContext()), Toast.LENGTH_LONG).show()
            viewModel.onFailureShown()
        }
        binding.errorNotice.isVisible = failure != null && moment == null
        binding.retryButton.isVisible = failure != null && moment == null
        if (failure != null && moment == null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun renderOwn(state: MomentUiState, moment: Moment) {
        val ends = moment.expiresAt
        binding.lifeLine.isVisible = true
        binding.lifeLine.text = when {
            moment.status == MomentStatus.WAITING -> getString(R.string.moment_life_waiting)
            moment.status == MomentStatus.ENDED -> getString(R.string.moment_life_ended)
            ends != null -> getString(R.string.moment_life_until, clock(ends))
            else -> getString(R.string.moment_life_live)
        }
        val keepable = state.canKeep || state.canUnkeep
        binding.keepButton.isVisible = keepable
        binding.keepHint.isVisible = state.canKeep
        binding.keepButton.setText(if (state.canUnkeep) R.string.moment_action_unkeep else R.string.moment_action_keep)
        binding.keepButton.isEnabled = !state.busy
        binding.deleteButton.isEnabled = !state.busy
    }

    private fun renderAuthor(moment: Moment) {
        val profileId = moment.author.profileId ?: return
        binding.authorButton.isVisible = true
        binding.authorButton.text = getString(R.string.moment_open_author, moment.author.name)
        binding.authorButton.setOnClickListener {
            findNavController().navigate(R.id.personProfileFragment, bundleOf("profileId" to profileId))
        }
    }

    /** The author's own exact time, in their phone's zone and clock style. */
    private fun clock(at: Instant): String {
        val pattern = if (DateFormat.is24HourFormat(requireContext())) "EEE HH:mm" else "EEE h:mm a"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(at.atZone(ZoneId.systemDefault()))
    }

    companion object {
        const val ARG_ID = "momentId"
        /** Set on the previous screen's saved state when the author deleted the moment. */
        const val RESULT_REMOVED = "moment_removed"
    }
}
