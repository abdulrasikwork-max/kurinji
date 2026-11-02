package com.safesphere.app.feature.profile.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
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
import com.safesphere.app.feature.social.domain.DecisionAction
import com.safesphere.app.feature.social.domain.PauseLevel
import com.safesphere.app.feature.social.ui.DailyStates
import com.safesphere.app.feature.social.ui.JournalUiState
import com.safesphere.app.feature.social.ui.JournalViewModel
import com.safesphere.app.feature.social.ui.MarksFit
import com.safesphere.app.feature.social.ui.ModerationLabels
import com.safesphere.app.feature.social.ui.PhotoStatus
import com.safesphere.app.feature.social.ui.cardImages
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportUiState
import com.safesphere.app.feature.social.ui.ReportViewModel
import com.safesphere.app.feature.social.ui.SocialLabels
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * Phase 13F (docs/PHASE13F_PLAN.md F18), redesigned in 17E: the You tab. The profile head on the ink panel (photo,
 * today's state; no counts), the social standing notice
 * (moved from Connections), then rows that open the person's own screens: experiences, connections, private places,
 * the compact Safety grid (opens the existing Safety screens; the SOS tile keeps its emergency look), reading choices, community, moderation (moderators only) and account. A hub between the social and
 * safety features: it only navigates; neither side's logic lives here.
 */
class YouFragment : ViewBindingFragment<FragmentYouBinding>(FragmentYouBinding::inflate) {

    private val account by viewModels<ProfileViewModel> { ProfileViewModel.factory(appContainer) }
    private val journal by viewModels<JournalViewModel> { JournalViewModel.factory(appContainer) }
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }

    /** Phase 17E: the system photo picker (no storage permission; only the chosen photo is shared with the app). */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) journal.onPhotoPicked(com.safesphere.app.feature.social.data.UriPick(uri,
            com.safesphere.app.feature.social.domain.MediaKind.PHOTO))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nav = findNavController()
        binding.editProfileButton.setOnClickListener { nav.navigate(R.id.profileSetupFragment) }
        binding.makeProfileButton.setOnClickListener { nav.navigate(R.id.profileSetupFragment) }
        binding.logoutButton.setOnClickListener { confirmLogout() }
        stackHeadForLargeText()

        binding.journalList.adapter = FeatureListAdapter(listOf(
            FeatureItem(R.drawable.ic_nav_discover, R.string.social_your_experiences, R.string.you_experiences_description) {
                nav.navigate(R.id.myPostsFragment)
            },
            FeatureItem(R.drawable.ic_group, R.string.title_connections, R.string.you_connections_description) {
                nav.navigate(R.id.connectionsFragment)
            },
            FeatureItem(R.drawable.ic_lock, R.string.social_private_places, R.string.you_private_places_description) {
                nav.navigate(R.id.privatePlacesFragment)
            }
        ))
        // Phase 17E: the compact Safety grid only opens the existing Safety screens.
        binding.tileSos.setOnClickListener { nav.navigate(R.id.emergencyFragment) }
        tile(binding.tileWalk, R.drawable.ic_route, R.string.you_tile_walk, R.string.you_tile_walk_sub) {
            nav.navigate(R.id.safeWalkFragment)
        }
        tile(binding.tileCircle, R.drawable.ic_group, R.string.you_tile_circle, R.string.you_tile_circle_sub) {
            nav.navigate(R.id.safetyCircleFragment)
        }
        tile(binding.tileNearby, R.drawable.ic_place, R.string.you_tile_nearby, R.string.you_tile_nearby_sub) {
            nav.navigate(R.id.communityFragment)
        }
        binding.allSafetyButton.setOnClickListener { nav.navigate(R.id.safetyFragment) }

        // Phase 17E: the profile photo (picked without any permission; made square on the phone) and today's state.
        binding.photoBlock.setOnClickListener { photoMenu() }
        binding.dailyStateRow.setOnClickListener {
            val me = journal.uiState.value.profile ?: return@setOnClickListener
            DailyStates.pick(this, me.dailyState) { journal.onDailyState(it) }
        }
        binding.communityList.adapter = FeatureListAdapter(listOf(
            FeatureItem(R.drawable.ic_info, R.string.guidelines_title, R.string.you_guidelines_description) {
                nav.navigate(R.id.guidelinesFragment)
            },
            FeatureItem(R.drawable.ic_history, R.string.my_reports_title, R.string.you_reports_description) {
                nav.navigate(R.id.myReportsFragment)
            },
            FeatureItem(R.drawable.ic_person_outline, R.string.title_blocked_muted, R.string.you_blocked_description) {
                nav.navigate(R.id.blockedMutedFragment)
            }
        ))
        binding.moderationList.adapter = FeatureListAdapter(listOf(
            FeatureItem(R.drawable.ic_shield_outline, R.string.profile_moderation_title,
                R.string.profile_moderation_description) { nav.navigate(R.id.moderationFragment) }
        ))
        binding.accountList.adapter = FeatureListAdapter(listOf(
            FeatureItem(R.drawable.ic_person_outline, R.string.profile_account_title, R.string.profile_account_description) {
                nav.navigate(R.id.accountFragment)
            },
            FeatureItem(R.drawable.ic_lock, R.string.profile_privacy_title, R.string.profile_privacy_description) {
                nav.navigate(R.id.privacyPolicyFragment)
            },
            FeatureItem(R.drawable.ic_tune, R.string.profile_settings_title, R.string.profile_settings_description) {
                nav.navigate(R.id.settingsFragment)
            },
            FeatureItem(R.drawable.ic_info, R.string.profile_about_title, R.string.profile_about_description) {
                nav.navigate(R.id.aboutFragment)
            }
        ))

        // Phase 13E (E20): the viewer's own choice, on this phone only.
        val preferences = appContainer.viewerPreferences
        collectWhenStarted(preferences.collapseStrongLanguage) { on ->
            if (binding.strongLanguageSwitch.isChecked != on) binding.strongLanguageSwitch.isChecked = on
        }
        binding.strongLanguageSwitch.setOnCheckedChangeListener { button, on ->
            if (button.isPressed) viewLifecycleOwner.lifecycleScope.launch { preferences.setCollapseStrongLanguage(on) }
        }

        collectWhenStarted(journal.uiState, ::renderJournal)
        collectWhenStarted(reports.uiState, ::renderStanding)
        collectWhenStarted(account.uiState) { state ->
            binding.accountLine.text = listOfNotNull(state.displayName, state.email).joinToString(" · ")
            binding.accountLine.isVisible = state.displayName != null || state.email != null
        }
        collectWhenStarted(account.canModerate) { binding.moderationSection.isVisible = it }
        collectWhenStarted(account.isLoggedOut) { loggedOut ->
            if (loggedOut) nav.navigate(R.id.action_global_to_login)
        }

        if (savedInstanceState == null) Motion.revealInSequence(listOf(binding.headerBlock))
    }

    override fun onStart() {
        super.onStart()
        journal.load()
        reports.loadStanding()
        account.refreshAccess()
    }

    private fun renderJournal(state: JournalUiState) {
        binding.progress.isVisible = state.isLoading && !state.hasLoaded
        val me = state.profile
        binding.noProfileCard.isVisible = state.hasLoaded && me == null
        binding.editProfileButton.isVisible = me != null
        binding.youName.text = me?.displayName ?: getString(R.string.nav_you)
        binding.photoBlock.isVisible = me != null
        cardImages().avatar(binding.youAvatar, me?.displayName, me?.handle, me?.publicId, me?.photo)
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
        // D-7: today's state (connections see it until midnight).
        binding.dailyStateRow.isVisible = me != null
        val today = me?.dailyState
        binding.dailyStateChip.text = today?.let { getString(R.string.daily_state_line, getString(DailyStates.label(it))) }
            ?: getString(R.string.daily_state_set)
        binding.dailyStateChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
            today?.let(DailyStates::icon) ?: R.drawable.ic_add, 0, 0, 0)
        binding.dailyStateRow.isEnabled = !state.stateBusy
        binding.dailyStateRow.contentDescription = listOf(binding.dailyStateChip.text, getString(R.string.daily_state_own_note))
            .joinToString(". ")
        if (state.stateSaved) {
            journal.onStateSavedShown()
            binding.dailyStateRow.announceForAccessibility(getString(R.string.daily_state_saved))
        }
        binding.youHandle.isVisible = me != null
        binding.youHandle.text = me?.let {
            if (it.city.isNullOrBlank()) getString(R.string.social_handle_line, it.handle)
            else getString(R.string.you_handle_city, it.handle, it.city)
        }.orEmpty()
        binding.youBio.isVisible = !me?.bio.isNullOrBlank()
        binding.youBio.text = me?.bio.orEmpty()
        val interests = me?.interests.orEmpty().map { getString(SocialLabels.of(it)) }
        binding.youInterests.isVisible = interests.isNotEmpty()
        binding.youInterests.text = interests.joinToString(" · ")

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

    private fun tile(tile: com.safesphere.app.databinding.ItemSafetyTileBinding, icon: Int, title: Int, subtitle: Int,
                     open: () -> Unit) {
        tile.icon.setImageResource(icon)
        tile.title.setText(title)
        tile.subtitle.setText(subtitle)
        tile.root.contentDescription = getString(title) + ". " + getString(subtitle)
        tile.root.setOnClickListener { open() }
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

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_dialog_title)
            .setMessage(R.string.logout_dialog_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_log_out) { _, _ -> account.onLogoutConfirmed() }
            .show()
    }
}
