package com.safesphere.app.feature.profile.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
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
import com.safesphere.app.feature.social.ui.JournalUiState
import com.safesphere.app.feature.social.ui.JournalViewModel
import com.safesphere.app.feature.social.ui.ModerationLabels
import com.safesphere.app.feature.social.ui.ReportMessage
import com.safesphere.app.feature.social.ui.ReportUiState
import com.safesphere.app.feature.social.ui.ReportViewModel
import com.safesphere.app.feature.social.ui.SocialLabels
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/**
 * Phase 13F (docs/PHASE13F_PLAN.md F18): the You tab. The journal header (no counts), the social standing notice
 * (moved from Connections), then rows that open the person's own screens: experiences, connections, private places,
 * the Safety hub, reading choices, community, moderation (moderators only) and account. A hub between the social and
 * safety features: it only navigates; neither side's logic lives here.
 */
class YouFragment : ViewBindingFragment<FragmentYouBinding>(FragmentYouBinding::inflate) {

    private val account by viewModels<ProfileViewModel> { ProfileViewModel.factory(appContainer) }
    private val journal by viewModels<JournalViewModel> { JournalViewModel.factory(appContainer) }
    private val reports by viewModels<ReportViewModel> { ReportViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nav = findNavController()
        binding.editProfileButton.setOnClickListener { nav.navigate(R.id.profileSetupFragment) }
        binding.makeProfileButton.setOnClickListener { nav.navigate(R.id.profileSetupFragment) }
        binding.logoutButton.setOnClickListener { confirmLogout() }

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
        binding.safetyList.adapter = FeatureListAdapter(listOf(
            FeatureItem(R.drawable.ic_shield_outline, R.string.you_safety_title, R.string.you_safety_description) {
                nav.navigate(R.id.safetyFragment)
            }
        ))
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
        binding.youAvatar.isVisible = me != null
        binding.youAvatar.set(me?.displayName, me?.handle)
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
        binding.standingNotice.isVisible = pause != null || warning != null
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

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_dialog_title)
            .setMessage(R.string.logout_dialog_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_log_out) { _, _ -> account.onLogoutConfirmed() }
            .show()
    }
}
