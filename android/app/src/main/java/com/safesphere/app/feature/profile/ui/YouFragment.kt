package com.safesphere.app.feature.profile.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.components.FeatureItem
import com.safesphere.app.core.ui.components.FeatureListAdapter
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentYouBinding
import com.safesphere.app.feature.membership.domain.MembershipState
import com.safesphere.app.feature.membership.ui.MembershipUiState
import com.safesphere.app.feature.membership.ui.MembershipViewModel
import com.safesphere.app.feature.social.domain.DecisionAction
import com.safesphere.app.feature.social.domain.PauseLevel
import com.safesphere.app.feature.social.ui.JournalUiState
import com.safesphere.app.feature.social.ui.JournalViewModel
import com.safesphere.app.feature.social.ui.MarksFit
import com.safesphere.app.feature.social.ui.ModerationLabels
import com.safesphere.app.feature.social.ui.PhotoStatus
import com.safesphere.app.feature.social.ui.CommunityLine
import com.safesphere.app.feature.social.ui.personImages
import com.safesphere.app.feature.moments.ui.MomentFragment
import com.safesphere.app.feature.moments.ui.MomentListAdapter
import com.safesphere.app.feature.moments.ui.ProfileMomentRows
import com.safesphere.app.feature.moments.ui.momentImages
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportUiState
import com.safesphere.app.feature.social.ui.ReportViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The You tab is a profile (N4): the head on the panel (photo, real name, handle, programme and year, bio; no counts),
 * the membership card and the end of the new-member week, the social standing notice, the person's own moments (on
 * Today now, and kept), then three quiet doors: My people, Safety (the existing Safety screens, unchanged) and
 * "Settings and more" ([MoreFragment]). It only reads and navigates. Your own page is not screenshot-protected (D12).
 */
class YouFragment : ViewBindingFragment<FragmentYouBinding>(FragmentYouBinding::inflate) {

    private val journal by viewModels<JournalViewModel> { JournalViewModel.factory(appContainer) }
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }
    private val membership by viewModels<MembershipViewModel> { MembershipViewModel.factory(appContainer) }

    /** Phase 17E: the system photo picker (no storage permission; only the chosen photo is shared with the app). */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) journal.onPhotoPicked(com.safesphere.app.feature.social.data.UriPick(uri))
    }

    private lateinit var momentAdapter: MomentListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nav = findNavController()
        binding.editProfileButton.setOnClickListener { nav.navigate(R.id.profileSetupFragment) }
        binding.makeProfileButton.setOnClickListener { nav.navigate(R.id.profileSetupFragment) }
        stackHeadForLargeText()

        momentAdapter = MomentListAdapter(momentImages(), onOpen = { moment ->
            nav.navigate(R.id.momentFragment, bundleOf(MomentFragment.ARG_ID to moment.id))
        }, onUncover = {})
        binding.momentList.adapter = momentAdapter

        // What is yours is above; then three quiet doors.
        binding.journalList.adapter = FeatureListAdapter(listOf(
            FeatureItem(R.drawable.ic_group, R.string.title_connections, R.string.you_connections_description) {
                nav.navigate(R.id.connectionsFragment)
            },
            FeatureItem(R.drawable.ic_shield_outline, R.string.nav_safety, R.string.you_safety_description) {
                nav.navigate(R.id.safetyFragment)
            },
            FeatureItem(R.drawable.ic_tune, R.string.title_more, R.string.you_more_description) {
                nav.navigate(R.id.moreFragment)
            }
        ))

        // Phase 17E: the profile photo (picked without any permission; made square on the phone).
        binding.photoBlock.setOnClickListener { photoMenu() }

        binding.membershipButton.setOnClickListener { nav.navigate(R.id.membershipFragment) }

        collectWhenStarted(journal.uiState, ::renderJournal)
        collectWhenStarted(reports.uiState, ::renderStanding)
        collectWhenStarted(membership.uiState, ::renderMembership)

        if (savedInstanceState == null) Motion.revealInSequence(listOf(binding.headerBlock))
    }

    override fun onStart() {
        super.onStart()
        journal.load()
        reports.loadStanding()
        membership.load(withProgrammes = false)
    }

    /** Phase N3: the person's own membership, one card; the button opens the membership screen. */
    private fun renderMembership(state: MembershipUiState) {
        val status = state.status
        binding.membershipCard.isVisible = status != null
        if (status == null) return
        val info = status.membership
        binding.membershipTitle.setText(when (status.state) {
            MembershipState.NONE -> R.string.you_membership_none
            MembershipState.PENDING -> R.string.you_membership_pending
            MembershipState.MEMBER -> R.string.you_membership_member
            MembershipState.ENDED -> R.string.you_membership_ended
            MembershipState.BANNED -> R.string.you_membership_banned
        })
        binding.membershipDetail.text = when (status.state) {
            MembershipState.MEMBER -> if (info != null) getString(R.string.membership_member_detail, info.programmeLabel,
                info.graduationYear) else status.communityName.orEmpty()
            MembershipState.PENDING -> getString(R.string.you_membership_pending_detail)
            MembershipState.BANNED -> getString(R.string.membership_safety_unchanged)
            else -> getString(R.string.you_membership_none_detail)
        }
        binding.membershipButton.setText(when (status.state) {
            MembershipState.NONE, MembershipState.ENDED -> R.string.action_get_verified
            else -> R.string.you_membership_open
        })
    }

    private fun renderJournal(state: JournalUiState) {
        binding.progress.isVisible = state.isLoading && !state.hasLoaded
        val me = state.profile
        binding.noProfileCard.isVisible = state.hasLoaded && me == null
        binding.editProfileButton.isVisible = me != null
        binding.youName.text = me?.displayName ?: getString(R.string.nav_you)
        binding.photoBlock.isVisible = me != null
        personImages().avatar(binding.youAvatar, me?.displayName, me?.handle, me?.publicId, me?.photo)
        val photoBusy = state.photo == PhotoStatus.PREPARING || state.photo == PhotoStatus.UPLOADING
        binding.photoProgress.isVisible = photoBusy
        binding.photoBlock.contentDescription = getString(when {
            photoBusy -> R.string.profile_photo_uploading
            me?.photo == null -> R.string.profile_photo_add
            else -> R.string.profile_photo_change
        })
        when (state.photo) {
            PhotoStatus.FAILED, PhotoStatus.UNREADABLE -> {
                Toast.makeText(requireContext(), if (state.photo == PhotoStatus.FAILED) R.string.profile_photo_failed
                    else R.string.profile_photo_unreadable, Toast.LENGTH_LONG).show()
                journal.onPhotoNoticeShown()
            }
            else -> Unit
        }
        binding.youHandle.isVisible = me != null
        binding.youHandle.text = me?.let { getString(R.string.social_handle_line, it.handle) }.orEmpty()
        binding.youBio.isVisible = !me?.bio.isNullOrBlank()
        binding.youBio.text = me?.bio.orEmpty()
        val community = CommunityLine.of(me?.programme, me?.graduationYear)
        binding.youCommunity.isVisible = community != null
        binding.youCommunity.text = community.orEmpty()
        // N4 (M2): no shadow rules: the person is told when their first week ends.
        val newUntil = me?.newMemberUntil
        binding.newMemberLine.isVisible = newUntil != null
        if (newUntil != null) binding.newMemberLine.text = getString(R.string.you_new_member_until,
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(newUntil.atZone(ZoneId.systemDefault())))

        binding.momentList.isVisible = state.momentsLoaded
        momentAdapter.submitList(if (state.momentsLoaded) ProfileMomentRows.own(state.current, state.kept) else emptyList())

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null && !state.hasLoaded
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            journal.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    /**
     * Phase 13D: a pause in force (with its end date and reason) or a recent reminder, in amber, with the review request;
     * and the reminder that reading, blocking, muting, reporting and safety tools are untouched.
     */
    private fun renderStanding(state: ReportUiState) {
        val standing = state.standing
        val pause = standing?.pause
        val warning = standing?.decisions?.firstOrNull { it.action == DecisionAction.PERSON_WARN }
        // Phase 17E: a removed profile photo is told to its owner (no shadow moderation).
        val photoRemoved = standing?.decisions?.firstOrNull { it.action == DecisionAction.PERSON_REMOVE_PHOTO }
        binding.standingNotice.isVisible = pause != null || warning != null || photoRemoved != null
        binding.standingLine.isVisible = pause != null
        val reviewDecision = pause?.decisionId?.takeIf { pause.canAppeal }
        binding.standingReviewButton.isVisible = reviewDecision != null
        binding.standingReviewButton.isEnabled = !state.isBusy
        binding.standingReviewButton.setOnClickListener { reviewDecision?.let(reports::appeal) }
        if (pause != null) {
            val until = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(pause.until.atZone(ZoneId.systemDefault()))
            val reason = getString(ModerationLabels.of(pause.reason))
            binding.standingNotice.setMessage(getString(when (pause.level) {
                PauseLevel.COMMENTS_PAUSED -> R.string.standing_comments_paused
                PauseLevel.POSTING_PAUSED -> R.string.standing_posting_paused
                PauseLevel.SUSPENDED -> R.string.standing_suspended
            }, until, reason))
            val appealLine = ModerationLabels.appealLine(pause.appeal)
            binding.standingLine.text = listOfNotNull(getString(R.string.standing_unaffected),
                appealLine?.let { getString(it) }).joinToString("\n")
        } else if (warning != null) {
            binding.standingNotice.setMessage(getString(R.string.standing_warning, getString(ModerationLabels.of(warning.reason))))
        } else if (photoRemoved != null) {
            binding.standingNotice.setMessage(getString(R.string.standing_photo_removed,
                getString(ModerationLabels.of(photoRemoved.reason))))
        }
        state.message?.let {
            if (it == ReportMessage.REVIEW_ASKED) Toast.makeText(requireContext(), R.string.review_asked, Toast.LENGTH_LONG).show()
            reports.onMessageShown()
        }
        state.failure?.let {
            Toast.makeText(requireContext(), it.toMessage(requireContext()), Toast.LENGTH_LONG).show()
            reports.onFailureShown()
        }
    }

    /** Phase 17E: add or change the profile photo, or remove it. */
    private fun photoMenu() {
        val me = journal.uiState.value.profile ?: return
        val pick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        // Who sees it, and what is removed from it, said before a photo is chosen (no surprise publication).
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_photo_description)
            .setMessage(R.string.profile_photo_note)
            .setPositiveButton(if (me.photo == null) R.string.profile_photo_add else R.string.profile_photo_change) { _, _ ->
                pick()
            }
            .setNegativeButton(R.string.action_cancel, null)
        if (me.photo != null) dialog.setNeutralButton(R.string.profile_photo_remove) { _, _ -> journal.onRemovePhoto() }
        dialog.show()
    }

    /**
     * Phase 17E: with large text on a narrow screen (the same budget at which feed cards drop to icons) the name and
     * handle get the full width under the photo, instead of a sliver beside it that broke the handle into pieces.
     */
    private fun stackHeadForLargeText() {
        val config = resources.configuration
        if (MarksFit.mode(config.screenWidthDp, config.fontScale) != MarksFit.Mode.ICONS) return
        binding.youHeadRow.orientation = android.widget.LinearLayout.VERTICAL
        binding.youHeadRow.gravity = android.view.Gravity.START
        binding.youNameColumn.layoutParams = (binding.youNameColumn.layoutParams as android.widget.LinearLayout.LayoutParams)
            .apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                marginStart = 0
                topMargin = (12 * resources.displayMetrics.density).toInt()
            }
    }

}
