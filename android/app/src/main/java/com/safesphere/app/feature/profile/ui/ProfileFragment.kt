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
import com.safesphere.app.core.ui.showComingLaterDialog
import com.safesphere.app.databinding.FragmentProfileBinding

/**
 * Safety Profile. Shows the signed-in user's name and email and offers Settings, Privacy, About and Log out;
 * the sections for the future safety features are Coming soon placeholders.
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

    private fun safetyItems(): List<FeatureItem> = listOf(
        FeatureItem(
            R.drawable.ic_group, R.string.feature_safety_circle_title, R.string.feature_safety_circle_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.safetyCircleFragment) },
        comingSoon(R.drawable.ic_shield_outline, R.string.profile_emergency_prefs_title, R.string.profile_emergency_prefs_description),
        comingSoon(R.drawable.ic_history, R.string.profile_activity_title, R.string.profile_activity_description),
        comingSoon(R.drawable.ic_community_outline, R.string.profile_contributions_title, R.string.profile_contributions_description)
    )

    private fun appItems(): List<FeatureItem> {
        val navController = findNavController()
        return listOf(
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

    private fun comingSoon(iconRes: Int, titleRes: Int, descriptionRes: Int) = FeatureItem(
        iconRes, titleRes, descriptionRes, BadgeStatus.COMING_SOON
    ) { showComingLaterDialog(titleRes) }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.logout_dialog_title)
            .setMessage(R.string.logout_dialog_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_log_out) { _, _ -> viewModel.onLogoutConfirmed() }
            .show()
    }
}
