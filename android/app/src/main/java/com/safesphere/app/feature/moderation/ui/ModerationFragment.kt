package com.safesphere.app.feature.moderation.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import com.safesphere.app.databinding.FragmentModerationBinding
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.moderation.domain.AppealItem

/**
 * Moderation queue (Phase 7): open cases and open appeals. Reached only from Profile when the server says this account
 * may moderate; the server re-checks the role on every call. FLAG_SECURE while open: case data (blocks of places under
 * review) stays out of screenshots, screen recordings and the recent-apps preview.
 */
class ModerationFragment : ViewBindingFragment<FragmentModerationBinding>(FragmentModerationBinding::inflate) {

    private val viewModel by viewModels<ModerationViewModel> { ModerationViewModel.factory(appContainer) }

    private val caseAdapter = CaseAdapter { case ->
        findNavController().navigate(R.id.action_moderation_to_case, bundleOf("caseId" to case.id))
    }
    private val appealAdapter = AppealAdapter(::confirmAppeal)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.caseList.adapter = caseAdapter
        binding.appealList.adapter = appealAdapter
        collectWhenStarted(viewModel.ui, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.load() // also after coming back from a case
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    private fun render(ui: ModerationUiState) {
        val queue = ui.queue
        binding.progress.isVisible = queue is QueueState.Loading || ui.busy
        binding.content.isVisible = queue is QueueState.Ready
        binding.statusNotice.isVisible = queue is QueueState.NotAllowed || queue is QueueState.Failed
        when (queue) {
            QueueState.NotAllowed -> {
                binding.statusNotice.setMessage(getString(R.string.moderation_not_allowed))
                caseAdapter.submitList(emptyList()) // nothing stays on screen once access is gone
                appealAdapter.submitList(emptyList())
            }
            is QueueState.Failed -> binding.statusNotice.setMessage(queue.failure.toMessage(requireContext()))
            is QueueState.Ready -> {
                caseAdapter.submitList(queue.cases)
                appealAdapter.submitList(queue.appeals)
                binding.noCases.isVisible = queue.cases.isEmpty()
                binding.noAppeals.isVisible = queue.appeals.isEmpty()
            }
            QueueState.Loading -> Unit
        }
        ui.event?.let {
            val text = when (it) {
                is ModerationEvent.AppealDecided -> getString(
                    if (it.decision == AppealStatus.OVERTURNED) R.string.appeal_decided_overturned
                    else R.string.appeal_decided_upheld
                )
                is ModerationEvent.Failed -> it.failure.toMessage(requireContext())
            }
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
            viewModel.onEventShown()
        }
        if (ui.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun confirmAppeal(appeal: AppealItem, overturn: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (overturn) R.string.action_overturn else R.string.action_uphold)
            .setMessage(if (overturn) R.string.appeal_confirm_overturn else R.string.appeal_confirm_uphold)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_record) { _, _ ->
                viewModel.onDecideAppeal(appeal, if (overturn) AppealStatus.OVERTURNED else AppealStatus.UPHELD)
            }
            .show()
    }
}
