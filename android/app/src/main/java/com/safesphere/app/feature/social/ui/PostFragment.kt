package com.safesphere.app.feature.social.ui

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.ui.ButterflyFlight
import com.safesphere.app.core.ui.Haptics
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentPostBinding
import com.safesphere.app.feature.going.GoingThere
import com.safesphere.app.feature.social.domain.ButterflyState
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
    private var shownFailure: ApiResult.Failure? = null
    private var writeBeenHere = false

    private lateinit var photoAdapter: PhotoAdapter
    private val perspectiveAdapter = PersonRowAdapter()
    private lateinit var kindChips: ChoiceChips<CommentKind>
    private lateinit var butterfly: ButterflyDrawable

    /** Phase 13F: the flight plays only when a tap's save has really been confirmed (null → WANT seen after the tap). */
    private var flyAfterSave = false
    private var shownButterfly: ButterflyState? = null
    private var butterflyKnown = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        writeBeenHere = savedInstanceState == null &&
            arguments?.getBoolean(PostViewModel.ARG_WRITE_BEEN_HERE) == true
        photoAdapter = PhotoAdapter(appContainer.imageLoader)
        binding.photoList.adapter = photoAdapter
        binding.perspectiveList.adapter = perspectiveAdapter
        kindChips = ChoiceChips(binding.kindGroup) { it?.let(viewModel::onKind) }
        binding.perspectiveInput.doAfterTextChanged { viewModel.onText(it?.toString().orEmpty()) }
        // Phase 13E: a private nudge first (the draft is checked on this phone only).
        binding.sendButton.setOnClickListener {
            DraftNudgeDialogs.beforeSharing(this, viewModel.uiState.value.text, onlyMe = false) { viewModel.onSend() }
        }
        binding.cancelReplyButton.setOnClickListener { viewModel.onCancelReply() }
        binding.deletePostButton.setOnClickListener { confirmDelete() }
        butterfly = ButterflyDrawable.intent(requireContext())
        binding.butterflyButton.icon = butterfly
        binding.butterflyButton.setOnClickListener {
            flyAfterSave = viewModel.uiState.value.post?.yourButterfly == null
            viewModel.onButterfly()
        }
        binding.beenButton.setOnClickListener { viewModel.onBeen() }
        binding.showCollapsedButton.setOnClickListener { viewModel.onReveal() }
        binding.reportPostButton.setOnClickListener {
            val id = viewModel.uiState.value.post?.publicId ?: return@setOnClickListener
            ReportDialogs.show(this, ReportTarget.POST) { reason, note -> reports.report(ReportTarget.POST, id, reason, note) }
        }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(reports.uiState, ::renderReports)
        collectWhenStarted(appContainer.viewerPreferences.collapseStrongLanguage, viewModel::onStrongLanguageFilter)
    }

    /** Phase 13D: confirmations of the report and review flows, and their failures. */
    private fun renderReports(state: ReportUiState) {
        state.message?.let {
            Toast.makeText(requireContext(), when (it) {
                ReportMessage.REPORT_SENT -> R.string.report_sent
                ReportMessage.REVIEW_ASKED -> R.string.review_asked
                ReportMessage.CHECK_REVIEW_ASKED -> R.string.check_review_asked
            }, Toast.LENGTH_LONG).show()
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
        // Phase 13E: the automatic check's notice marks the words it noticed, in the author's own text.
        val phrases = DraftNudgeDialogs.phrases(ownText(post), moderation.flagged)
        binding.moderationNotice.setMessage(getString(ModerationLabels.ownNotice(moderation),
            getString(ModerationLabels.of(moderation.reason)),
            if (phrases.isEmpty()) "" else getString(R.string.own_flagged_phrase, phrases)))
        if (moderation.bySystem) {
            val asked = post.publicId in reportState.checkReviewAsked
            binding.reviewButton.setText(R.string.check_review_ask)
            binding.reviewButton.isVisible = moderation.canAskCheckReview && !asked
            binding.reviewButton.isEnabled = !reportState.isBusy
            binding.reviewButton.setOnClickListener { reports.askCheckReview(ReportTarget.POST, post.publicId) }
            binding.reviewLine.isVisible = asked
            if (asked) binding.reviewLine.setText(R.string.check_review_asked)
            return
        }
        binding.reviewButton.setText(R.string.review_ask)
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
        if (failure != null) {
            val message = failure.toMessage(requireContext())
            binding.errorNotice.setMessage(message)
            // Phase 15: the notice is at the top, the perspective's Add button at the bottom; bring a NEW failure
            // into view (and say it) so the tap does not look ignored.
            if (failure !== shownFailure) binding.errorNotice.post {
                binding.root.smoothScrollTo(0, binding.errorNotice.top)
                binding.errorNotice.announceForAccessibility(message)
            }
        }
        shownFailure = failure

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
        // Phase 13F: the same overline and postmark as the post's card; the postmark says only what the author revealed.
        val card = ExperienceCards.of(post)
        binding.typeLine.text = CardTexts.overline(requireContext(), card)
        binding.postmark.content = CardTexts.postmark(requireContext(), card)
        val author = post.author
        binding.byLine.isVisible = author != null
        binding.byLine.text = author?.let { getString(R.string.social_by_line, it.displayName) }.orEmpty()
        binding.byLine.setOnClickListener {
            if (author != null) findNavController().navigate(R.id.personProfileFragment,
                bundleOf(PersonProfileViewModel.ARG_PROFILE_ID to author.publicId))
        }
        // Phase 13D: a collapsed post shows why and "Show it"; its text and photos only after the tap.
        val hide = state.showCollapsed
        binding.collapsedBlock.isVisible = hide
        binding.collapsedNotice.setMessage(getString(ModerationLabels.collapsedLine(post.collapsed)))
        val (headline, rest) = PostPageText.split(post.body)
        binding.headline.isVisible = headline != null && !hide
        binding.headline.text = headline.orEmpty()
        binding.body.isVisible = !hide && rest.isNotEmpty()
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
        binding.body.text = rest
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
        onButterflyState(post.yourButterfly)
        binding.beenButton.isVisible = post.yourButterfly == ButterflyState.WANT
        // Phase 13F (F9): from reading to going. The sheet only navigates; the save is this screen's own butterfly.
        binding.goingButton.isVisible = state.canButterfly
        binding.goingButton.setOnClickListener {
            GoingThere.show(this, GoingThere.Target(CardTexts.goingLabel(card)),
                GoingThere.CocoonChoice(saved = post.yourButterfly != null) {
                    flyAfterSave = true
                    viewModel.onButterfly()
                })
        }
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

        // Phase 15: opened to write "Been here too": once the perspectives are loaded (isLoading) AND their rows are in
        // the list (the commit callback; the diff runs in the background), so the box is where it will stay.
        val beenHereNow = writeBeenHere && !state.isLoading
        if (beenHereNow) writeBeenHere = false
        val startWriting = beenHereNow && post.canComment && CommentKind.BEEN_HERE in state.kinds
        perspectiveAdapter.submitList(state.perspectives.map { row(it, post, state) }) {
            if (startWriting && view != null) startBeenHere()
        }
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
            p.ownModeration?.let {
                add(getString(if (it.bySystem) R.string.own_perspective_system_line else R.string.own_perspective_line,
                    ownLine(it)))
            }
        }
        val collapsed = state.perspectiveCollapsed(p)
        val text = if (!collapsed) p.body else getString(ModerationLabels.collapsedLine(p.collapsed))
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

    /**
     * Phase 13F, the first moment: when a tap's save is confirmed (no state → WANT), the butterfly beats its wings and
     * flies into the Cocoon tab, with one haptic and a spoken confirmation. On BEEN it flutters once. With animations off,
     * only the state, the haptic and the announcement change.
     */
    private fun onButterflyState(state: ButterflyState?) {
        val previous = shownButterfly
        val known = butterflyKnown
        shownButterfly = state
        butterflyKnown = true
        if (!known || previous == state) return
        if (previous == null && state == ButterflyState.WANT && flyAfterSave) {
            flyAfterSave = false
            Haptics.confirm(binding.butterflyButton)
            binding.butterflyButton.announceForAccessibility(getString(R.string.butterfly_saved_announcement))
            ButterflyFlight.fly(binding.butterflyButton, requireActivity().findViewById(R.id.cocoonFragment)) { }
        } else if (state == ButterflyState.BEEN && ValueAnimator.areAnimatorsEnabled()) {
            ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f).apply {
                duration = 420
                addUpdateListener { butterfly.flap = it.animatedValue as Float }
            }.start()
        }
    }

    /** Phase 13E: the text the server checked (body, a new line, the tip): flagged offsets point into it. */
    private fun ownText(post: Post): String = if (post.tip == null) post.body else post.body + "\n" + post.tip

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
        if (p.ownModeration?.canAskCheckReview == true) add(getString(R.string.check_review_ask) to {
            reports.askCheckReview(ReportTarget.COMMENT, p.publicId)
        })
        if (p.own) add(getString(R.string.social_delete_perspective) to { viewModel.onDeletePerspective(p) })
        if (!p.own) add(getString(R.string.report_perspective) to {
            ReportDialogs.show(this@PostFragment, ReportTarget.COMMENT) { reason, note ->
                reports.report(ReportTarget.COMMENT, p.publicId, reason, note)
            }
        })
    }

    /** Phase 13C (design §3.4.2): after "Been", the invitation to share what was found, for the next person. */
    private fun showBeenInvitation(post: Post) {
        BeenInvitation.show(this, post.canComment,
            onPerspective = ::startBeenHere,
            onOwn = { findNavController().navigate(R.id.composeFragment) })
    }

    private fun startBeenHere() {
        viewModel.onKind(CommentKind.BEEN_HERE)
        binding.root.doOnPreDraw {
            val box = Rect()
            binding.writeBlock.getDrawingRect(box)
            binding.root.offsetDescendantRectToMyCoords(binding.writeBlock, box)
            binding.root.scrollTo(0, (box.bottom - binding.root.height).coerceAtLeast(0))
            binding.perspectiveInput.requestFocus()
        }
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
