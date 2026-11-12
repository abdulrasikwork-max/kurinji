package com.safesphere.app.feature.profile.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.components.FeatureItem
import com.safesphere.app.core.ui.components.FeatureListAdapter
import com.safesphere.app.databinding.FragmentMoreBinding
import kotlinx.coroutines.launch

/**
 * IRL redesign (R4): "Settings and more", reached from You. The rows that used to fill the lower half of the You tab:
 * private places, the reading choice, community, moderation (moderators only), account and log out. It only navigates;
 * nothing was removed, only moved one tap away so the You tab can be a profile.
 */
class MoreFragment : ViewBindingFragment<FragmentMoreBinding>(FragmentMoreBinding::inflate) {

    private val account by viewModels<ProfileViewModel> { ProfileViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nav = findNavController()
        binding.privacyList.adapter = FeatureListAdapter(listOf(
            FeatureItem(R.drawable.ic_lock, R.string.social_private_places, R.string.you_private_places_description) {
                nav.navigate(R.id.privatePlacesFragment)
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
        binding.logoutButton.setOnClickListener { confirmLogout() }

        // Phase 13E (E20): the viewer's own choice, on this phone only.
        val preferences = appContainer.viewerPreferences
        collectWhenStarted(preferences.collapseStrongLanguage) { on ->
            if (binding.strongLanguageSwitch.isChecked != on) binding.strongLanguageSwitch.isChecked = on
        }
        binding.strongLanguageSwitch.setOnCheckedChangeListener { button, on ->
            if (button.isPressed) viewLifecycleOwner.lifecycleScope.launch { preferences.setCollapseStrongLanguage(on) }
        }

        collectWhenStarted(account.uiState) { state ->
            binding.accountLine.text = listOfNotNull(state.displayName, state.email).joinToString(" · ")
            binding.accountLine.isVisible = state.displayName != null || state.email != null
        }
        collectWhenStarted(account.canModerate) { binding.moderationSection.isVisible = it }
        collectWhenStarted(account.isLoggedOut) { loggedOut ->
            if (loggedOut) nav.navigate(R.id.action_global_to_login)
        }
    }

    override fun onStart() {
        super.onStart()
        account.refreshAccess()
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
