package com.safesphere.app.feature.social.ui

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
import androidx.recyclerview.widget.PagerSnapHelper
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.ui.ButterflyFlight
import com.safesphere.app.core.ui.Haptics
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.feature.social.data.VideoRules
import com.safesphere.app.databinding.FragmentPostBinding
import com.safesphere.app.feature.going.GoingThere
import com.safesphere.app.feature.social.domain.CommentKind
import com.safesphere.app.feature.social.domain.ExploreFacets
import com.safesphere.app.feature.social.domain.ModerationState
import com.safesphere.app.feature.social.domain.OwnModeration
import com.safesphere.app.feature.social.domain.Perspective
import com.safesphere.app.feature.social.domain.Post
import com.safesphere.app.feature.social.domain.Precision
import com.safesphere.app.feature.social.domain.ReportTarget

/**
 * Phase 13B, rebuilt in Phase 16 (docs/PHASE16.md): one post as a social page. Who first (tap for their profile), the
 * photos large, every word of the post, where and when as chips (only what the author revealed), the Tip, then save /
 * (17E) the butterfly and Save, "I went" and Going there?, and the comments with the comment box always at the bottom. Report, delete and the place
 * page sit in the "more" menu. Times are bands; the comment count is the list the viewer reads (U1).
 */
class PostFragment : ViewBindingFragment<FragmentPostBinding>(FragmentPostBinding::inflate) {

    private val viewModel by viewModels<PostViewModel> { PostViewModel.factory(appContainer) }
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }
    private var standingAskedFor: String? = null
    private var shownFailure: ApiResult.Failure? = null
    private var scrollToComments = false

    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var commentAdapter: CommentAdapter
    private lateinit var kindChips: ChoiceChips<CommentKind>


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollToComments = savedInstanceState == null && arguments?.getBoolean(PostViewModel.ARG_TO_COMMENTS) == true
        photoAdapter = PhotoAdapter(appContainer.imageLoader, large = true)
        commentAdapter = CommentAdapter(cardImages())
        binding.photoList.adapter = photoAdapter
        PagerSnapHelper().attachToRecyclerView(binding.photoList)
        binding.perspectiveList.adapter = commentAdapter
        kindChips = ChoiceChips(binding.kindGroup) { it?.let(viewModel::onKind) }
        binding.perspectiveInput.doAfterTextChanged { viewModel.onText(it?.toString().orEmpty()) }
        // Phase 13E: a private nudge first (the draft is checked on this phone only).
        binding.sendButton.setOnClickListener {
            DraftNudgeDialogs.beforeSharing(this, viewModel.uiState.value.text, onlyMe = false) { viewModel.onSend() }
        }
        binding.cancelReplyButton.setOnClickListener { viewModel.onCancelReply() }
        binding.showCollapsedButton.setOnClickListener { viewModel.onReveal() }
        binding.moreButton.setOnClickListener { showMoreMenu() }
        wentSheet = WentSheet(this, onRate = viewModel::onRate, onClearRating = viewModel::onClearRating,
            onNotWent = viewModel::onNotWent, onShare = { viewModel.uiState.value.post?.let { composeFor(it) } })
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

    /** Phase 13D: the author's notice about their own post, with the reason and the review request. */
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

    /** Phase 17G-2 (U5): "How was it?" after "I went" (the person's own visit only). */
    private var wentSheet: WentSheet? = null

    private fun render(state: PostUiState) {
        binding.progress.isVisible = state.isLoading || state.isBusy
        wentSheet?.setBusy(state.visitBusy)
        if (state.visitSheet) {
            viewModel.onVisitSheetHandled()
            wentSheet?.show(state.visit)
        } else if (wentSheet?.isShowing == true) {
            wentSheet?.update(state.visit)
        }
        binding.notFound.isVisible = state.notFound
        val post = state.post
        binding.contentBlock.isVisible = post != null && !state.notFound
        binding.moreButton.isVisible = post != null
        if (post != null) show(post, state) else binding.writeBlock.isVisible = false

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) {
            val message = failure.toMessage(requireContext())
            binding.errorNotice.setMessage(message)
            // Phase 15: the notice is at the top, the comment box at the bottom; bring a NEW failure into view (and
            // say it) so the tap does not look ignored.
            if (failure !== shownFailure) binding.errorNotice.post {
                binding.scroll.smoothScrollTo(0, 0)
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
        val card = ExperienceCards.of(post)
        val author = post.author
        cardImages().avatar(binding.avatar, author?.displayName, author?.handle, author?.publicId, author?.photo)
        binding.authorName.text = author?.displayName ?: getString(R.string.feed_someone)
        binding.meta.text = listOfNotNull(CardTexts.meta(requireContext(), card),
            getString(R.string.social_edited).takeIf { post.edited }).joinToString(" · ")
        binding.questionBadge.isVisible = card.isAsk
        binding.authorRow.isClickable = author != null && !post.own
        binding.authorRow.contentDescription = author?.let { getString(R.string.post_author_description, it.displayName) }
        binding.authorRow.setOnClickListener {
            if (author != null && !post.own) findNavController().navigate(R.id.personProfileFragment,
                bundleOf(PersonProfileViewModel.ARG_PROFILE_ID to author.publicId))
        }

        // Phase 13D: a collapsed post shows why and "Show it"; its text and photos only after the tap.
        val hide = state.showCollapsed
        binding.collapsedBlock.isVisible = hide
        binding.collapsedNotice.setMessage(getString(ModerationLabels.collapsedLine(post.collapsed)))
        binding.body.isVisible = !hide
        binding.body.text = post.body.trim()
        photoAdapter.submitList(if (hide) emptyList() else
            post.media.map { media ->
                PhotoCell(media.mediaId, appContainer.experienceRepository.photoUrl(media.mediaId), null, null,
                    media.width, media.height,
                    // Phase 17C: a video shows its still and plays only when tapped (its own screen).
                    videoLength = media.durationMs?.takeIf { media.isVideo }?.let { VideoRules.durationLabel(it.toLong()) },
                    onOpen = if (media.isVideo) ({ openVideo(media.mediaId) }) else null)
            })

        binding.photoList.isVisible = post.media.isNotEmpty() && !hide
        showOwnModeration(post, reports.uiState.value)
        binding.approveFirstLine.isVisible = post.approveFirst && post.canComment
        factChips(post, card)
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

        // Phase 17E (D-3): the butterfly (Experiences) and Save on someone else's post; the author's private line on
        // their own. Then from reading to going: "I went" and "Going there?".
        binding.marksBlock.isVisible = !post.own
        CardBinder.bindReaction(binding.reactButton, post.youReacted, state.canReact) { viewModel.onReact() }
        CardBinder.bindSave(binding.saveButton, post.saved, state.canSave) { viewModel.onSave() }
        binding.wentButton.setOnClickListener { iWent(post) }
        // Phase 13F (F9): the sheet only navigates; its "Save" is this screen's own Save.
        binding.goingButton.setOnClickListener {
            GoingThere.show(this, GoingThere.Target(CardTexts.goingLabel(card)),
                GoingThere.CocoonChoice(saved = post.saved) { viewModel.onSave() })
        }
        val tally = post.butterflies
        binding.authorTallyLine.isVisible = post.own && tally != null
        binding.authorTallyLine.text = when {
            tally == null -> ""
            tally.fewerThanThree -> getString(R.string.butterfly_author_fewer)
            else -> getString(R.string.butterfly_author_count, tally.count ?: 0)
        }
        if (state.savedNow) {
            viewModel.onSavedShown()
            Haptics.confirm(binding.saveButton)
            binding.saveButton.announceForAccessibility(getString(R.string.save_announcement))
            ButterflyFlight.fly(binding.saveButton, requireActivity().findViewById(R.id.cocoonFragment)) { }
        }

        // Comments: the title carries the number the viewer can read (U1), the same as the list below it.
        val count = state.perspectives.size
        binding.commentsTitle.text = if (count == 0) getString(R.string.social_perspectives_title)
            else getString(R.string.post_comments_title_count, count)
        val toComments = scrollToComments && !state.isLoading
        if (toComments) scrollToComments = false
        commentAdapter.submitList(state.perspectives.map { row(it, post, state) }) {
            if (view == null) return@submitList
            if (toComments) scrollTo(binding.commentsTitle)
        }
        binding.perspectivesEmpty.isVisible = state.perspectives.isEmpty() && !state.isLoading
        binding.perspectivesEmpty.setText(if (post.canComment) R.string.social_perspectives_empty
            else R.string.post_comments_none)
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

    /** Where, when and what kind, as chips; the place chip opens the place's page (exact places only). */
    private fun factChips(post: Post, card: ExperienceCard) {
        val group = binding.factChips
        group.removeAllViews()
        CardTexts.placeChip(requireContext(), card)?.let { place ->
            val placeId = post.placeId
            group.addView(Chip(requireContext(), null, R.attr.contextChipStyle).apply {
                text = place
                setChipIconResource(R.drawable.ic_place)
                isChipIconVisible = true
                if (post.precision == Precision.EXACT && placeId != null) {
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(R.string.post_open_place_description, place)
                    setOnClickListener {
                        findNavController().navigate(R.id.postCollectionFragment,
                            bundleOf(PostCollectionViewModel.ARG_PLACE_ID to placeId))
                    }
                }
            })
        }
        CardTexts.whenLine(requireContext(), card)?.let { line ->
            group.addView(Chip(requireContext(), null, R.attr.contextChipStyle).apply {
                text = line
                setChipIconResource(R.drawable.ic_schedule)
                isChipIconVisible = true
            })
        }
        // Phase 17G-1: the author's "Good for..." tags, read-only (nothing counts or ranks them).
        val tags = post.facets.mapNotNull { ExploreFacets.find(it)?.english }
        if (tags.isNotEmpty()) {
            group.addView(Chip(requireContext(), null, R.attr.contextChipStyle).apply {
                text = getString(R.string.post_facets_line, tags.joinToString(" · "))
            })
        }
        group.isVisible = group.childCount > 0
    }

    private fun row(p: Perspective, post: Post, state: PostUiState): CommentRow {
        val kind = getString(ExperienceLabels.of(p.kind))
        val name = p.author?.displayName.orEmpty()
        val notes = buildList {
            if (p.helped) add(getString(R.string.social_helped))
            if (p.hidden) add(getString(R.string.social_hidden_by_author))
            // Phase 13D: waiting for approval (Q6); a moderator's state, to its writer only.
            if (p.awaitingApproval) add(getString(if (p.canApprove) R.string.perspective_awaiting_yours
                else R.string.perspective_awaiting_author))
            p.ownModeration?.let {
                add(getString(if (it.bySystem) R.string.own_perspective_system_line else R.string.own_perspective_line,
                    ownLine(it)))
            }
        }.joinToString(" · ")
        val collapsed = state.perspectiveCollapsed(p)
        val text = if (!collapsed) p.body else getString(ModerationLabels.collapsedLine(p.collapsed))
        val header = listOf(name, kind, getString(TimeBandTexts.of(p.postedPeriod))).filter { it.isNotEmpty() }
            .joinToString(" · ")
        val actions = actionsFor(p, collapsed)
        val canReply = post.canComment && p.parentId == null && !p.hidden
        return CommentRow(
            key = p.publicId,
            name = p.author?.displayName,
            handle = p.author?.handle,
            profileId = p.author?.publicId,
            photo = p.author?.photo,
            header = header,
            body = text,
            notes = notes,
            isReply = p.parentId != null,
            spoken = listOf(header, text, notes).filter { it.isNotBlank() }.joinToString(". "),
            replyDescription = getString(R.string.social_replying_to, name),
            onReply = if (canReply) ({ startReply(p) }) else null,
            onMore = if (actions.isNotEmpty()) ({
                MaterialAlertDialogBuilder(requireContext())
                    .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
                    .show()
            }) else null
        )
    }

    private fun startReply(p: Perspective) {
        viewModel.onReplyTo(p)
        binding.perspectiveInput.requestFocus()
    }

    /** The post's own menu: report someone else's post; delete your own; open the place. */
    private fun showMoreMenu() {
        val post = viewModel.uiState.value.post ?: return
        val items = buildList<Pair<String, () -> Unit>> {
            val placeId = post.placeId
            if (post.precision == Precision.EXACT && placeId != null) add(getString(R.string.post_open_place_page) to {
                findNavController().navigate(R.id.postCollectionFragment,
                    bundleOf(PostCollectionViewModel.ARG_PLACE_ID to placeId))
            })
            if (post.own) add(getString(R.string.social_delete_post) to { confirmDelete() })
            else add(getString(R.string.report_post) to {
                ReportDialogs.show(this@PostFragment, ReportTarget.POST) { reason, note ->
                    reports.report(ReportTarget.POST, post.publicId, reason, note)
                }
            })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Phase 13E: the text the server checked (body, a new line, the tip): flagged offsets point into it. */
    private fun ownText(post: Post): String = if (post.tip == null) post.body else post.body + "\n" + post.tip

    /** Phase 13D: the short line a writer sees about their own moderated comment. */
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

    /**
     * Phase 17E/17G-2 ("I went"): when the post names an exact place, "I went" marks the person's private visit and asks how it was
     * (the sheet then offers the composer); otherwise there is no place to mark and the composer opens as before.
     */
    private fun iWent(post: Post) {
        if (!viewModel.onWent()) composeFor(post)
    }

    /** The composer, with this post's place filled in when it named an exact place (and its category). Nothing is posted by itself. */
    private fun composeFor(post: Post) {
        val placeId = post.placeId.takeIf { post.precision == Precision.EXACT }
        findNavController().navigate(R.id.composeFragment, bundleOf(ComposeFragment.ARG_PLACE_ID to placeId,
            ComposeFragment.ARG_CATEGORY to post.category?.name))
    }

    private fun scrollTo(target: View) {
        binding.scroll.doOnPreDraw {
            var y = 0
            var v: View? = target
            while (v != null && v !== binding.scroll) {
                y += v.top
                v = v.parent as? View
            }
            binding.scroll.smoothScrollTo(0, y)
        }
    }

    /** Phase 17C: the player screen, only ever from a tap on the video. */
    private fun openVideo(mediaId: String) {
        findNavController().navigate(R.id.videoPlayerFragment, bundleOf(VideoPlayerFragment.ARG_MEDIA_ID to mediaId))
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
