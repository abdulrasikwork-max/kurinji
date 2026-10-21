package com.safesphere.app.feature.settings.ui

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.menu.MenuListAdapter
import com.safesphere.app.core.ui.menu.MenuListItem
import com.safesphere.app.core.ui.menu.MenuListItem.Header
import com.safesphere.app.core.ui.menu.MenuListItem.Row
import com.safesphere.app.databinding.FragmentSettingsBinding

/**
 * Settings categories. Rows marked isComingSoon are placeholders for later phases;
 * only the Account > Profile shortcut and the About/Terms/Privacy Policy pages work today.
 */
class SettingsFragment : ViewBindingFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.settingsList.adapter = MenuListAdapter(settingsItems())
    }

    private fun settingsItems(): List<MenuListItem> {
        val navController = findNavController()
        return listOf(
            Header(R.string.settings_section_account),
            Row(R.string.settings_profile) {
                // Settings is opened from Profile, so "Profile" simply goes back to it.
                if (!navController.popBackStack(R.id.profileFragment, false)) {
                    navController.navigate(R.id.profileFragment)
                }
            },
            Row(R.string.settings_change_password, isComingSoon = true),

            Header(R.string.settings_section_safety),
            Row(R.string.settings_emergency, isComingSoon = true),
            Row(R.string.settings_safety_circle, isComingSoon = true),
            Row(R.string.settings_checkin, isComingSoon = true),

            Header(R.string.settings_section_privacy),
            Row(R.string.settings_privacy_settings, isComingSoon = true),
            Row(R.string.settings_data_permissions, isComingSoon = true),

            Header(R.string.settings_section_application),
            Row(R.string.settings_notifications, isComingSoon = true),
            Row(R.string.settings_about) { navController.navigate(R.id.action_settings_to_about) },
            Row(R.string.settings_terms) { navController.navigate(R.id.action_settings_to_terms) },
            Row(R.string.settings_privacy_policy) {
                navController.navigate(R.id.action_settings_to_privacy_policy)
            }
        )
    }
}
