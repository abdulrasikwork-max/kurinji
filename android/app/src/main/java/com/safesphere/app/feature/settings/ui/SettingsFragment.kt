package com.safesphere.app.feature.settings.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
 * Settings (rebuilt in Phase 10A): only rows that do something. Each opens an existing screen or the phone's own
 * settings for this app (permissions, notifications); nothing here is a placeholder ("Coming soon" rows are not
 * allowed: SettingsHonestyTest). Preferences that belong to a feature stay on that feature's screen (automatic SMS
 * in Safety Circle, the recovery passphrase in the Evidence Vault, movement checks in Safe Walk).
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
            Row(R.string.settings_account) { navController.navigate(R.id.accountFragment) },

            Header(R.string.settings_section_safety),
            Row(R.string.settings_safety_circle) { navController.navigate(R.id.safetyCircleFragment) },
            Row(R.string.settings_evidence_vault) { navController.navigate(R.id.evidenceVaultFragment) },
            Row(R.string.settings_emergency_history) { navController.navigate(R.id.emergencyHistoryFragment) },

            Header(R.string.settings_section_privacy),
            Row(R.string.settings_privacy_policy) { navController.navigate(R.id.action_settings_to_privacy_policy) },
            Row(R.string.settings_app_permissions) { openSystemSettings(appDetailsIntent()) },
            Row(R.string.settings_notifications) { openSystemSettings(notificationSettingsIntent()) },

            Header(R.string.settings_section_application),
            Row(R.string.settings_about) { navController.navigate(R.id.action_settings_to_about) },
            Row(R.string.settings_terms) { navController.navigate(R.id.action_settings_to_terms) }
        )
    }

    /** The phone's own page for this app: which permissions are granted, and switching any of them off. */
    private fun appDetailsIntent() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))

    private fun notificationSettingsIntent() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)

    private fun openSystemSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // Some phones lack the specific page: fall back to this app's details page.
            runCatching { startActivity(appDetailsIntent()) }
        }
    }
}
