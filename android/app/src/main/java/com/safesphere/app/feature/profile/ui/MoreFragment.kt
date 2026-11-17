package com.safesphere.app.feature.profile.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.components.FeatureItem
import com.safesphere.app.core.ui.components.FeatureListAdapter
import com.safesphere.app.databinding.FragmentMoreBinding
import com.safesphere.app.feature.membership.ui.MembershipViewModel
import com.safesphere.app.feature.membership.ui.staff.StaffListFragment

/**
 * "Settings and more", reached from You: community (guidelines, your reports, blocked and muted), moderation and
 * community staff (only for those who hold the role), account and log out. It only navigates.
 */
class MoreFragment : ViewBindingFragment<FragmentMoreBinding>(FragmentMoreBinding::inflate) {

    private val account by viewModels<ProfileViewModel> { ProfileViewModel.factory(appContainer) }
    private val membership by viewModels<MembershipViewModel> { MembershipViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nav = findNavController()
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
        binding.logoutButton.setOnClickListener { confirmLogout() }

        collectWhenStarted(account.uiState) { state ->
            binding.accountLine.text = listOfNotNull(state.displayName, state.phoneHint).joinToString(" · ")
            binding.accountLine.isVisible = state.displayName != null || state.phoneHint != null
        }
        collectWhenStarted(account.canModerate) { binding.moderationSection.isVisible = it }
        // N3 (A2): the staff rows each person may use (only convenience: the server checks the role on every call).
        collectWhenStarted(membership.uiState) { state ->
            val status = state.status
            val verifier = status?.verifier == true
            val admin = status?.admin == true
            binding.staffSection.isVisible = verifier || admin
            if (!verifier && !admin) return@collectWhenStarted
            fun list(mode: StaffListFragment.Mode, title: Int) =
                nav.navigate(R.id.staffListFragment, StaffListFragment.args(mode, getString(title)))
            binding.staffList.adapter = FeatureListAdapter(listOfNotNull(
                FeatureItem(R.drawable.ic_check_circle, R.string.title_staff_decide, R.string.staff_decide_description) {
                    nav.navigate(R.id.staffDecideFragment)
                },
                FeatureItem(R.drawable.ic_camera, R.string.staff_photos_title, R.string.staff_photos_description) {
                    list(StaffListFragment.Mode.PHOTOS, R.string.staff_photos_title)
                }.takeIf { admin },
                FeatureItem(R.drawable.ic_group, R.string.staff_verifiers_title, R.string.staff_verifiers_description) {
                    list(StaffListFragment.Mode.VERIFIERS, R.string.staff_verifiers_title)
                }.takeIf { admin },
                FeatureItem(R.drawable.ic_flag, R.string.staff_bans_title, R.string.staff_bans_description) {
                    list(StaffListFragment.Mode.BANS, R.string.staff_bans_title)
                }.takeIf { admin },
                // N4 (A3): the daily question pinned on Today.
                FeatureItem(R.drawable.ic_check_circle, R.string.title_questions_admin, R.string.questions_admin_row_description) {
                    nav.navigate(R.id.questionsAdminFragment)
                }.takeIf { admin },
                FeatureItem(R.drawable.ic_tune, R.string.title_staff_actions, R.string.staff_actions_description) {
                    nav.navigate(R.id.staffActionsFragment)
                }.takeIf { admin },
                FeatureItem(R.drawable.ic_history, R.string.staff_log_title, R.string.staff_log_description) {
                    list(StaffListFragment.Mode.LOG, R.string.staff_log_title)
                }.takeIf { admin }
            ))
        }
        collectWhenStarted(account.isLoggedOut) { loggedOut ->
            if (loggedOut) nav.navigate(R.id.action_global_to_login)
        }
    }

    override fun onStart() {
        super.onStart()
        account.refreshAccess()
        membership.load(withProgrammes = false)
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
