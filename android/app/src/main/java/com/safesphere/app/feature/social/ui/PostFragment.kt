package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
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
import com.safesphere.app.databinding.FragmentPostBinding
import com.safesphere.app.feature.social.domain.ButterflyState
import com.safesphere.app.feature.social.domain.Collapsed
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.ModerationState
import com.safesphere.app.feature.social.domain.OwnModeration
import com.safesphere.app.feature.social.domain.ReportTarget
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision

/**
 * Phase 13B: one post (design §9.4, §9.8). The place line says only what the author chose to show; times are bands;
 * the author sees who can see it and, for an exact place, from when others can. Perspectives: Been here too first;
 * a hidden one is marked "Hidden by the author" for its writer; more actions in a short menu on each row.
 */
class PostFragment : ViewBindingFragment<FragmentPostBinding>(FragmentPostBinding::inflate) {

    private val viewModel by viewModels<PostViewModel> { PostViewModel.factory(appContainer) }
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }
    private var standingAskedFor: String? = null

    private lateinit var photoAdapter: PhotoAdapter
    private val perspectiveAdapter = PersonRowAdapter()
    private lateinit var kindChips: ChoiceChips<CommentKind>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        photoAdapter = PhotoAdapter(appContainer.imageLoader)
        binding.photoList.adapter = photoAdapter
        binding.perspectiveList.adapter = perspectiveAdapter
        kindChips = ChoiceChips(binding.kindGroup) { it?.let(viewModel::onKind) }
        binding.perspectiveInput.doAfterTextChanged { viewModel.onText(it?.toString().orEmpty()) }
        binding.sendButton.setOnClickListener { viewModel.onSend() }
        binding.cancelReplyButton.setOnClickListener { viewModel.onCancelReply() }
        binding.deletePostButton.setOnClickListener { confirmDelete() }
        binding.butterflyButton.setOnClickListener { viewModel.onButterfly() }
        binding.beenButton.setOnClickListener { viewModel.onBeen() }
        binding.showCollapsedButton.setOnClickListener { viewModel.onReveal() }
        binding.reportPostButton.setOnClickListener {
            val id = viewModel.uiState.value.post?.publicId ?: return@setOnClickListener
            ReportDialogs.show(this, ReportTarget.POST) { reason, note -> reports.report(ReportTarget.POST, id, reason, note) }
        }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(reports.uiState, ::renderReports)
    }

    /** Phase 13D: confirmations of the report and review flows, and their failures. */
    private fun renderReports(state: ReportUiState) {
        state.message?.let {
            Toast.makeText(requireContext(),
                if (it == ReportMessage.REPORT_SENT) R.string.report_sent else R.string.review_asked,
                Toast.LENGTH_LONG).show()
            reports.onMessageShown()
        }
        state.failure?.let {
            Toast.makeText(requireContext(), it.toMessage(requireContext()), Toast.LENGTH_LONG).show()
            reports.onFailureShown()
        }
        if (state.sessionExpired) {
            reports.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
        viewModel.uiState.value.post?.let { showOwnModeration(it, state) }
    }

    /** Phase 13D: the author's amber notice about their own post, with the reason and the review request. */
    private fun showOwnModeration(post: Post, reportState: ReportUiState) {
        val moderation = post.ownModeration
        binding.moderationNotice.isVisible = moderation != null
        if (moderation == null) {
            binding.reviewButton.isVisible = false
            binding.reviewLine.isVisible = false
            return
        }
        binding.moderationNotice.setMessage(getString(ModerationLabels.ownNotice(moderation),
            getString(ModerationLabels.of(moderation.reason))))
        val decisionId = moderation.decisionId
        if (decisionId != null && standingAskedFor != decisionId) {
            standingAskedFor = decisionId
            reports.loadStanding()
        }
        val decision = reportState.standing?.decisionFor(decisionId)
        binding.reviewButton.isVisible = decision?.canAppeal == true
        binding.reviewButton.isEnabled = !reportState.isBusy
        binding.reviewButton.setOnClickListener { if (decisionId != null) reports.appeal(decisionId) }
        val line = decision?.let { ModerationLabels.appealLine(it.appeal) }
        binding.reviewLine.isVisible = line != null
        if (line != null) binding.reviewLine.setText(line)
    }

    private fun render(state: PostUiState) {
        binding.progress.isVisible = state.isLoading || state.isBusy
        binding.notFound.isVisible = state.notFound
        val post = state.post
        binding.contentBlock.isVisible = post != null && !state.notFound
        if (post != null) show(post, state)

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        if (state.closed) {
            viewModel.onClosedHandled()
            Toast.makeText(requireContext(), R.string.social_post_deleted, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun show(post: Post, state: PostUiState) {
        binding.typeLine.text = getString(if (post.type == PostType.ASK) R.string.social_ask_badge else R.string.social_type_experience)
        val author = post.author
        binding.byLine.text = author?.let { getString(R.string.social_by_line, it.displayName) }.orEmpty()
        binding.byLine.setOnClickListener {
            if (author != null) findNavController().navigate(R.id.personProfileFragment,
                bundleOf(PersonProfileViewModel.ARG_PROFILE_ID to author.publicId))
        }
        // Phase 13D: a collapsed post shows why and "Show it"; its text and photos only after the tap.
        val hide = state.showCollapsed
        binding.collapsedBlock.isVisible = hide
        binding.collapsedNotice.setMessage(getString(
            if (post.collapsed == Collapsed.BY_MODERATOR) R.string.collapsed_by_moderator else R.string.collapsed_under_review))
        binding.body.isVisible = !hide
        photoAdapter.submitList(if (hide) emptyList() else
            post.media.map { PhotoCell(it.mediaId, appContainer.experienceRepository.photoUrl(it.mediaId), null, null) })
        binding.photoList.isVisible = post.media.isNotEmpty() && !hide
        showOwnModeration(post, reports.uiState.value)
        binding.reportPostButton.isVisible = !post.own
        binding.approveFirstLine.isVisible = post.approveFirst && post.canComment

        binding.placeLine.text = when (post.precision) {
            Precision.EXACT -> post.placeName?.let { getString(R.string.social_place_line, it) } ?: getString(R.string.social_no_place_line)
            Precision.AREA -> post.areaCell?.let { getString(R.string.social_area_line, it) } ?: getString(R.string.social_no_place_line)
            Precision.CITY -> post.city?.let { getString(R.string.social_city_line_post, ExperienceLabels.cityName(it)) }
                ?: getString(R.string.social_no_place_line)
            Precision.NONE -> getString(R.string.social_no_place_line)
        }
        val month = post.experiencedMonth?.let { ExperienceLabels.monthName(it) }
        val daypart = post.daypart?.let { getString(ExperienceLabels.of(it)) }
        binding.whenLine.text = when {
            month != null && daypart != null -> getString(R.string.social_when_line, month, daypart)
            else -> month ?: daypart.orEmpty()
        }
        binding.whenLine.isVisible = month != null || daypart != null
        val band = getString(TimeBandTexts.of(post.postedPeriod))
        binding.bandLine.text = if (post.edited) getString(R.string.social_when_line, band, getString(R.string.social_edited)) else band
        binding.body.text = post.body
        binding.tipCard.isVisible = !post.tip.isNullOrBlank() && !hide
        binding.tip.text = post.tip.orEmpty()

        val ownLines = buildList {
            post.audience?.let { add(getString(R.string.social_own_audience_line, getString(ExperienceLabels.of(it)))) }
            val from = post.visibleFrom
            // Only an exact place is delayed (Q1); a phone clock a little behind the server must not invent a delay.
            if (post.own && post.precision == Precision.EXACT && from != null && from.isAfter(java.time.Instant.now())) {
                add(getString(R.string.social_own_delay_line, DateUtils.formatDateTime(requireContext(), from.toEpochMilli(),
                    DateUtils.FORMAT_SHOW_TIME)))
            }
        }
        binding.ownLine.text = ownLines.joinToString("\n")
        binding.ownLine.isVisible = ownLines.isNotEmpty()
        binding.deletePostButton.isVisible = post.own

        // Phase 13C: the butterfly on someone else's Experience; the author's private, thresholded line on their own.
        binding.butterflyBlock.isVisible = state.canButterfly
        binding.butterflyButton.isEnabled = !state.isBusy
        binding.beenButton.isEnabled = !state.isBusy
        when (post.yourButterfly) {
            null -> {
                binding.butterflyButton.setText(R.string.butterfly_want)
                binding.butterflyButton.contentDescription = getString(R.string.butterfly_want_description)
            }
            ButterflyState.WANT -> {
                binding.butterflyButton.setText(R.string.butterfly_saved_want)
                binding.butterflyButton.contentDescription = getString(R.string.butterfly_remove_description)
            }
            ButterflyState.BEEN -> {
                binding.butterflyButton.setText(R.string.butterfly_saved_been)
                binding.butterflyButton.contentDescription = getString(R.string.butterfly_remove_description)
            }
        }
        binding.beenButton.isVisible = post.yourButterfly == ButterflyState.WANT
        val tally = post.butterflies
        binding.authorTallyLine.isVisible = post.own && tally != null
        binding.authorTallyLine.text = when {
            tally == null -> ""
            tally.fewerThanThree -> getString(R.string.butterfly_author_fewer)
            else -> getString(R.string.butterfly_author_count, tally.count ?: 0)
        }
        val placeId = post.placeId
        binding.placePageButton.isVisible = post.precision == Precision.EXACT && placeId != null
        binding.placePageButton.setOnClickListener {
            if (placeId != null) findNavController().navigate(R.id.postCollectionFragment,
                bundleOf(PostCollectionViewModel.ARG_PLACE_ID to placeId))
        }
        if (state.beenInvitation) {
            viewModel.onBeenInvitationHandled()
            showBeenInvitation(post)
        }

        perspectiveAdapter.submitList(state.perspectives.map { row(it, post, state) })
        binding.perspectivesEmpty.isVisible = state.perspectives.isEmpty()
        binding.writeBlock.isVisible = post.canComment
        binding.perspectivesClosed.isVisible = !post.canComment && !post.own
        kindChips.show(state.kinds.map { it to getString(ExperienceLabels.of(it)) }, state.kind)
        binding.kindGroup.isVisible = state.replyTo == null
        binding.replyBlock.isVisible = state.replyTo != null
        binding.replyingTo.text = state.replyTo?.let {
            getString(R.string.social_replying_to, it.author?.displayName.orEmpty())
        }.orEmpty()
        if (state.text.isEmpty() && !binding.perspectiveInput.text.isNullOrEmpty()) binding.perspectiveInput.setText("")
        binding.sendButton.isEnabled = state.canSend
    }

    private fun row(p: Perspective, post: Post, state: PostUiState): PersonRow {
        val kind = getString(ExperienceLabels.of(p.kind))
        val name = p.author?.displayName.orEmpty()
        val extras = buildList {
            add(getString(TimeBandTexts.of(p.postedPeriod)))
            if (p.helped) add(getString(R.string.social_helped))
            if (p.hidden) add(getString(R.string.social_hidden_by_author))
            // Phase 13D: waiting for approval (Q6); a moderator's state, to its writer only.
            if (p.awaitingApproval) add(getString(if (p.canApprove) R.string.perspective_awaiting_yours
                else R.string.perspective_awaiting_author))
            p.ownModeration?.let { add(getString(R.string.own_perspective_line, ownLine(it))) }
        }
        val collapsed = p.collapsed != null && p.publicId !in state.revealedPerspectives
        val text = if (!collapsed) p.body else getString(
            if (p.collapsed == Collapsed.BY_MODERATOR) R.string.collapsed_by_moderator else R.string.collapsed_under_review)
        val actions = actionsFor(p, collapsed)
        return PersonRow(
            key = p.publicId,
            title = (if (p.parentId != null) "↳ " else "") + "$name · $kind",
            subtitle = text + "\n" + extras.joinToString(" · "),
            primary = if (post.canComment && p.parentId == null && !p.hidden) RowAction(getString(R.string.social_reply),
                getString(R.string.social_replying_to, name)) { viewModel.onReplyTo(p) } else null,
            secondary = if (actions.isNotEmpty()) RowAction(getString(R.string.action_more), getString(R.string.action_more)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                    .show()
            } else null
        )
    }

    /** Phase 13D: the short line a writer sees about their own moderated perspective. */
    private fun ownLine(moderation: OwnModeration): String {
        val state = getString(when (moderation.state) {
            ModerationState.COLLAPSED -> R.string.content_state_collapsed
            ModerationState.HELD -> R.string.content_state_held
            ModerationState.REMOVED -> R.string.content_state_removed
        })
        return if (moderation.reason == null) state else "$state (${getString(ModerationLabels.of(moderation.reason))})"
    }

    private fun actionsFor(p: Perspective, collapsed: Boolean): List<Pair<String, () -> Unit>> = buildList {
        if (collapsed) add(getString(R.string.collapsed_show) to { viewModel.onRevealPerspective(p) })
        if (p.canApprove) add(getString(R.string.perspective_approve) to { viewModel.onApprove(p) })
        if (p.canHide) {
            if (p.hidden) add(getString(R.string.social_unhide) to { viewModel.onHide(p, false) })
            else add(getString(R.string.social_hide) to { viewModel.onHide(p, true) })
        }
        if (p.canMarkHelped) {
            if (p.helped) add(getString(R.string.social_helped_unmark) to { viewModel.onHelped(p, false) })
            else add(getString(R.string.social_helped_mark) to { viewModel.onHelped(p, true) })
        }
        if (p.own) add(getString(R.string.social_delete_perspective) to { viewModel.onDeletePerspective(p) })
        if (!p.own) add(getString(R.string.report_perspective) to {
            ReportDialogs.show(this@PostFragment, ReportTarget.COMMENT) { reason, note ->
                reports.report(ReportTarget.COMMENT, p.publicId, reason, note)
            }
        })
    }

    /** Phase 13C (design §3.4.2): after "Been", the invitation to share what was found, for the next person. */
    private fun showBeenInvitation(post: Post) {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.been_invitation_title)
            .setMessage(R.string.been_invitation_message)
            .setNeutralButton(R.string.been_invitation_later, null)
            .setNegativeButton(R.string.been_invitation_own) { _, _ -> findNavController().navigate(R.id.composeFragment) }
        if (post.canComment) {
            builder.setPositiveButton(R.string.been_invitation_perspective) { _, _ ->
                viewModel.onKind(CommentKind.BEEN_HERE)
                binding.perspectiveInput.requestFocus()
            }
        }
        builder.show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.social_delete_post_title)
            .setMessage(R.string.social_delete_post_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove) { _, _ -> viewModel.onDeletePostConfirmed() }
            .show()
    }
}
