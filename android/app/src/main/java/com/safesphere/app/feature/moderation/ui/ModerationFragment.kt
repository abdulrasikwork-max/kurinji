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
import com.safesphere.app.feature.moderation.domain.ContentAppeal
import com.safesphere.app.feature.social.ui.PersonRow
import com.safesphere.app.feature.social.ui.PersonRowAdapter
import com.safesphere.app.feature.social.ui.RowAction

/**
 * The moderators' queue (Phase 13D): reported posts, perspectives and people, and the requests to look again at those
 * decisions. Reached only when the server says this account may moderate; the server re-checks the role on every call.
 * FLAG_SECURE while open. (The Phase 7 queue of Nearby-signal cases was retired in N1.)
 */
class ModerationFragment : ViewBindingFragment<FragmentModerationBinding>(FragmentModerationBinding::inflate) {

    private val content by viewModels<ContentQueueViewModel> { ContentQueueViewModel.factory(appContainer) }
    private val contentCaseAdapter = PersonRowAdapter()
    private val contentAppealAdapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.contentCaseList.adapter = contentCaseAdapter
        binding.contentAppealList.adapter = contentAppealAdapter
        collectWhenStarted(content.uiState, ::renderContent)
    }

    override fun onStart() {
        super.onStart()
        content.load() // also after coming back from a case
    }

    private fun renderContent(state: ContentQueueUiState) {
        binding.progress.isVisible = state.isLoading
        binding.contentQueue.isVisible = state.allowed
        // Not a moderator (or no longer one), or the queue could not be read: say which, never an empty screen.
        val problem = state.answered && !state.allowed
        binding.statusNotice.isVisible = problem
        if (problem) {
            binding.statusNotice.setMessage(state.failure?.toMessage(requireContext())
                ?: getString(R.string.moderation_not_allowed))
        }
        contentCaseAdapter.submitList(state.cases.map { c ->
            val reason = getString(ContentModerationTexts.of(c.topReason))
            PersonRow(
                key = c.caseId,
                title = getString(R.string.content_case_row, getString(ContentModerationTexts.of(c.target)), reason),
                subtitle = listOfNotNull(
                    getString(R.string.content_case_counts, c.independent.toString()),
                    c.contentState?.let { getString(R.string.content_case_state, getString(ContentModerationTexts.state(it))) },
                    if (c.youTookPart) getString(R.string.content_case_you_took_part) else null
                ).joinToString("\n"),
                onOpen = { openContentCase(c.caseId) },
                openDescription = getString(R.string.content_case_title)
            )
        })
        binding.noContentCases.isVisible = state.cases.isEmpty()
        contentAppealAdapter.submitList(state.appeals.map { a ->
            val decided = getString(ContentModerationTexts.of(a.action))
            PersonRow(
                key = a.appealId,
                title = getString(R.string.content_appeal_row, getString(ContentModerationTexts.of(a.target)), decided),
                subtitle = listOfNotNull(getString(ContentModerationTexts.of(a.reason)),
                    if (a.youDecided) getString(R.string.content_appeal_yours) else null).joinToString("\n"),
                primary = if (!a.youDecided) RowAction(getString(R.string.content_appeal_undo),
                    getString(R.string.content_appeal_undo)) { confirmContentAppeal(a, true) } else null,
                secondary = if (!a.youDecided) RowAction(getString(R.string.content_appeal_keep),
                    getString(R.string.content_appeal_keep)) { confirmContentAppeal(a, false) } else null,
                onOpen = a.caseId?.let { id -> { openContentCase(id) } },
                openDescription = getString(R.string.content_appeal_open_case)
            )
        })
        binding.noContentAppeals.isVisible = state.appeals.isEmpty()
        if (state.appealDecided) {
            content.onAppealDecidedShown()
            Toast.makeText(requireContext(), R.string.content_appeal_decided, Toast.LENGTH_SHORT).show()
        }
        if (state.sessionExpired) {
            content.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun openContentCase(caseId: String) =
        findNavController().navigate(R.id.contentCaseFragment, bundleOf("caseId" to caseId))

    private fun confirmContentAppeal(appeal: ContentAppeal, overturn: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (overturn) R.string.content_appeal_undo else R.string.content_appeal_keep)
            .setMessage(R.string.content_action_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_record) { _, _ -> content.onDecideAppeal(appeal, overturn) }
            .show()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }
}
