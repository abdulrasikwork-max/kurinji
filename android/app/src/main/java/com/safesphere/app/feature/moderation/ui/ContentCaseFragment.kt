package com.safesphere.app.feature.moderation.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.textcheck.TextSpans
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentContentCaseBinding
import com.safesphere.app.feature.moderation.domain.CaseRole
import com.safesphere.app.feature.moderation.domain.ContentAction
import com.safesphere.app.feature.moderation.domain.ContentCaseDetail
import com.safesphere.app.feature.moderation.domain.ContentModerationPolicy
import com.safesphere.app.feature.moderation.domain.ContentTarget
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase 13D: one reported post, perspective or person, for a moderator. People appear only as case pseudonyms; a person
 * case shows the public profile. Photos stay hidden until the moderator taps (D15). Each decision asks for a reason
 * code and, when it changes what others see or can do, a confirmation. FLAG_SECURE while open.
 */
class ContentCaseFragment : ViewBindingFragment<FragmentContentCaseBinding>(FragmentContentCaseBinding::inflate) {

    private val viewModel by viewModels<ContentCaseViewModel> { ContentCaseViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    private fun render(state: ContentCaseUiState) {
        binding.progress.isVisible = state.isLoading || state.isBusy
        val detail = state.detail
        binding.contentBlock.isVisible = detail != null && !state.notAllowed
        val failure = state.failure
        binding.statusNotice.isVisible = state.notAllowed || failure != null
        when {
            state.notAllowed -> binding.statusNotice.setMessage(getString(R.string.content_case_you_took_part))
            failure != null -> binding.statusNotice.setMessage(failure.toMessage(requireContext()))
        }
        if (detail != null && !state.notAllowed) show(detail, state)
        if (state.decided) {
            viewModel.onDecidedShown()
            Toast.makeText(requireContext(), R.string.content_action_done, Toast.LENGTH_SHORT).show()
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    /**
     * Phase 13E: what the automatic check found, with the phrase it noticed (in the case's text: a post's body, a new
     * line and its tip; or the perspective), and ALWAYS the reminder that it is evidence, not a verdict.
     */
    private fun checkLines(detail: ContentCaseDetail): List<String> {
        val check = detail.check ?: return emptyList()
        val content = detail.content
        val text = if (content.commentKind != null || content.tip == null) content.body.orEmpty()
        else content.body.orEmpty() + "\n" + content.tip
        return listOf(getString(R.string.content_case_check, check.action.lowercase().replace('_', ' '),
            check.rule.lowercase().replace('_', ' '), check.versions)) +
            check.findings.map { f ->
                getString(R.string.content_case_check_finding, f.category.lowercase().replace('_', ' '), f.severity,
                    (if (f.targeted) getString(R.string.content_case_check_targeted) else "") +
                        (if (f.quoted) getString(R.string.content_case_check_quoted) else ""),
                    f.span?.let { TextSpans.phrase(text, it) }.orEmpty())
            } + getString(R.string.content_case_check_guidance)
    }

    private fun show(detail: ContentCaseDetail, state: ContentCaseUiState) {
        val summary = detail.summary
        binding.headline.text = getString(R.string.content_case_row, getString(ContentModerationTexts.of(summary.target)),
            getString(ContentModerationTexts.of(summary.topReason)))
        val facts = buildList {
            when (summary.openedBy) {
                "SYSTEM" -> add(getString(R.string.content_case_opened_by_system))
                "AUTHOR_REQUEST" -> add(getString(R.string.content_case_opened_by_author))
            }
            add(getString(R.string.content_case_counts, summary.independent.toString()))
            summary.contentState?.let {
                add(getString(if (summary.contentSource == "SYSTEM") R.string.content_case_state_system
                    else R.string.content_case_state, getString(ContentModerationTexts.state(it))))
            }
            detail.content.audience?.let { add(getString(R.string.content_case_audience, it.lowercase())) }
            if (!summary.open) add(getString(R.string.content_case_closed))
            addAll(checkLines(detail))
        }
        binding.facts.text = facts.joinToString("\n")

        val content = detail.content
        binding.contentText.text = when (summary.target) {
            ContentTarget.PERSON -> content.person?.let { p ->
                listOfNotNull(getString(R.string.content_case_person, p.displayName, p.handle), p.bio, p.city,
                    p.interests.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString("\n")
            }.orEmpty()
            else -> listOfNotNull(content.body, content.tip?.let { "→ $it" }, content.placeName, content.areaCell,
                content.city, content.contextBody?.let { getString(R.string.content_case_context, it) },
                *detail.revisions.map { getString(R.string.content_case_earlier_text, it.body) }.toTypedArray()
            ).joinToString("\n\n")
        }

        binding.photosNote.isVisible = content.media.isNotEmpty()
        binding.photoBlock.removeAllViews()
        content.media.forEachIndexed { index, media ->
            if (media.mediaId in state.shownPhotos) {
                val image = ImageView(requireContext()).apply {
                    adjustViewBounds = true
                    contentDescription = getString(R.string.content_case_show_photo, (index + 1).toString())
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                image.load(viewModel.photoUrl(media.mediaId), appContainer.imageLoader)
                binding.photoBlock.addView(image)
            } else {
                binding.photoBlock.addView(textButton(getString(R.string.content_case_show_photo, (index + 1).toString())) {
                    viewModel.onShowPhoto(media.mediaId)
                })
            }
        }

        binding.peopleLines.text = detail.participants.joinToString("\n") { p ->
            val role = when (p.role) {
                CaseRole.AUTHOR -> getString(R.string.content_role_author)
                CaseRole.SUBJECT -> getString(R.string.content_role_subject)
                CaseRole.REPORTER -> getString(R.string.content_role_reporter, getString(ContentModerationTexts.of(p.reason)))
            }
            val extras = listOfNotNull(
                if (p.role == CaseRole.REPORTER) getString(if (p.counting) R.string.content_case_counting
                else R.string.content_case_not_counting) else null,
                p.accountAge?.lowercase(),
                p.linkGroup?.let { getString(R.string.content_case_linked, it) },
                p.note?.let { getString(R.string.content_case_note, it) }
            )
            getString(R.string.content_case_participant, p.ref, (listOf(role) + extras).joinToString(" · "))
        }
        val author = detail.author
        binding.authorLine.text = listOfNotNull(
            author?.suggestedStep?.let { getString(R.string.content_case_suggestion, getString(ContentModerationTexts.of(it))) },
            author?.restrictedUntil?.let {
                getString(R.string.content_case_paused_until,
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it.atZone(ZoneId.systemDefault())))
            }
        ).joinToString("\n")
        binding.authorLine.isVisible = binding.authorLine.text.isNotEmpty()
        binding.decisionsLine.text = if (detail.decisions.isEmpty()) "" else getString(R.string.content_case_decisions,
            detail.decisions.joinToString(", ") { getString(ContentModerationTexts.of(it.action)) })
        binding.decisionsLine.isVisible = detail.decisions.isNotEmpty()

        binding.actionBlock.removeAllViews()
        for (action in state.actions) {
            binding.actionBlock.addView(textButton(getString(ContentModerationTexts.of(action))) {
                chooseReason(action, detail)
            }.apply { isEnabled = !state.isBusy })
        }
    }

    private fun chooseReason(action: ContentAction, detail: ContentCaseDetail) {
        val reasons = ContentModerationPolicy.reasonsFor(action)
        if (reasons.size == 1) {
            confirm(action) { viewModel.onAct(action, reasons.first()) }
            return
        }
        var chosen = reasons.indexOf(ContentModerationPolicy.defaultReason(action, detail))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.content_action_choose_reason)
            .setSingleChoiceItems(reasons.map { getString(ContentModerationTexts.of(it)) }.toTypedArray(), chosen) { _, which ->
                chosen = which
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_record) { _, _ ->
                val reason = reasons.getOrNull(chosen) ?: return@setPositiveButton
                confirm(action) { viewModel.onAct(action, reason) }
            }
            .show()
    }

    private fun confirm(action: ContentAction, then: () -> Unit) {
        if (!ContentModerationPolicy.needsConfirmation(action)) {
            then()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(ContentModerationTexts.of(action))
            .setMessage(R.string.content_action_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_record) { _, _ -> then() }
            .show()
    }

    private fun textButton(text: String, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            this.text = text
            minHeight = resources.getDimensionPixelSize(R.dimen.min_touch_target)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
}
