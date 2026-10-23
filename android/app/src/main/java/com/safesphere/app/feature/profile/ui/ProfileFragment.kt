package com.safesphere.app.feature.profile.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.components.BadgeStatus
import com.safesphere.app.core.ui.components.FeatureItem
import com.safesphere.app.core.ui.components.FeatureListAdapter
import androidx.navigation.navOptions
import com.safesphere.app.databinding.FragmentProfileBinding

/**
 * Safety Profile. Shows the signed-in user's name and email, links the person's own records (Safety Circle, emergency
 * history, Evidence Vault, Nearby reports) and offers Account and security, Privacy, Settings, About and Log out.
 */
class ProfileFragment : ViewBindingFragment<FragmentProfileBinding>(FragmentProfileBinding::inflate) {

    private val viewModel by viewModels<ProfileViewModel> { ProfileViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.safetyList.adapter = FeatureListAdapter(safetyItems())
        binding.appList.adapter = FeatureListAdapter(appItems())
        binding.moderationList.adapter = FeatureListAdapter(
            listOf(
                FeatureItem(
                    R.drawable.ic_shield_outline, R.string.profile_moderation_title,
                    R.string.profile_moderation_description, BadgeStatus.AVAILABLE
                ) { findNavController().navigate(R.id.action_profile_to_moderation) }
            )
        )
        binding.logoutButton.setOnClickListener { confirmLogout() }

        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(viewModel.canModerate) { binding.moderationSection.isVisible = it }
        collectWhenStarted(viewModel.isLoggedOut) { loggedOut ->
            if (loggedOut) findNavController().navigate(R.id.action_global_to_login)
        }

        if (savedInstanceState == null) {
            Motion.revealInSequence(
                listOf(binding.headerBlock, binding.safetySection, binding.appSection, binding.logoutButton)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshAccess()
    }

    private fun render(state: ProfileUiState) {
        binding.profileName.text = state.displayName ?: getString(R.string.profile_name_fallback)
        binding.profileEmail.text = state.email ?: getString(R.string.profile_email_fallback)
    }

    // Phase 10A: every row opens a screen that exists (the Coming-soon rows pointed at features that were built since).
    private fun safetyItems(): List<FeatureItem> = listOf(
        FeatureItem(
            R.drawable.ic_group, R.string.feature_safety_circle_title, R.string.feature_safety_circle_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.safetyCircleFragment) },
        FeatureItem(
            R.drawable.ic_history, R.string.profile_activity_title, R.string.profile_activity_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.emergencyHistoryFragment) },
        FeatureItem(
            R.drawable.ic_lock, R.string.feature_evidence_vault_title, R.string.feature_evidence_vault_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.evidenceVaultFragment) },
        FeatureItem(
            R.drawable.ic_community_outline, R.string.profile_contributions_title, R.string.profile_contributions_description,
            BadgeStatus.AVAILABLE
        ) {
            // Same as tapping the Nearby tab ("Your reports and answers" is on that screen).
            findNavController().navigate(R.id.communityFragment, null, navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(R.id.homeFragment) { saveState = true }
            })
        }
    )

    private fun appItems(): List<FeatureItem> {
        val navController = findNavController()
        return listOf(
            // Phase 13A: profiles and connections, here until the new navigation (13F).
            FeatureItem(
                R.drawable.ic_person, R.string.profile_social_title, R.string.profile_social_description,
                BadgeStatus.AVAILABLE
            ) { navController.navigate(R.id.connectionsFragment) },
            FeatureItem(
                R.drawable.ic_person_outline, R.string.profile_account_title, R.string.profile_account_description,
                BadgeStatus.AVAILABLE
            ) { navController.navigate(R.id.accountFragment) },
            FeatureItem(
                R.drawable.ic_lock, R.string.profile_privacy_title, R.string.profile_privacy_description,
                BadgeStatus.AVAILABLE
            ) { navController.navigate(R.id.action_profile_to_privacy_policy) },
            FeatureItem(
                R.drawable.ic_tune, R.string.profile_settings_title, R.string.profile_settings_description,
                BadgeStatus.AVAILABLE
            ) { navController.navigate(R.id.action_profile_to_settings) },
            FeatureItem(
                R.drawable.ic_info, R.string.profile_about_title, R.string.profile_about_description,
                BadgeStatus.AVAILABLE
            ) { navController.navigate(R.id.action_profile_to_about) }
        )
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_dialog_title)
            .setMessage(R.string.logout_dialog_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_log_out) { _, _ -> viewModel.onLogoutConfirmed() }
            .show()
    }
}
