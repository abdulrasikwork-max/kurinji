package com.safesphere.app.feature.safety.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.core.ui.RuntimePermissionAsk
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentSosOptionsBinding

/**
 * Phase 17F (D-4): You → Safety → SOS options. One switch, off until the person turns it on: shake to start the SOS
 * countdown (never starts anything without that cancellable countdown). Voice is not available and the screen says
 * so. (The switch for audio in an SOS left with the Evidence Vault in N1.)
 */
class SosOptionsFragment : ViewBindingFragment<FragmentSosOptionsBinding>(FragmentSosOptionsBinding::inflate) {

    private val viewModel by viewModels<SosOptionsViewModel> { SosOptionsViewModel.factory(appContainer) }

    private var askedForNotifications = false

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.shakeSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) viewModel.setShake(checked)
        }
        binding.shakeNotificationsButton.setOnClickListener { askForNotifications() }
        collectWhenStarted(viewModel.options) { render() }
    }

    override fun onStart() {
        super.onStart()
        render() // back from Settings: the permission may have changed
    }

    private fun render() {
        if (view == null) return
        val options = viewModel.options.value
        val shakeSwitch = binding.shakeSwitch

        val shakeOn = options?.shakeOn == true
        shakeSwitch.isEnabled = options != null && viewModel.shakeSensorAvailable
        if (shakeSwitch.isChecked != shakeOn) shakeSwitch.isChecked = shakeOn
        val shakeLine = when {
            !viewModel.shakeSensorAvailable -> R.string.sos_options_shake_no_sensor
            shakeOn && !NotificationPermission.isGranted(requireContext()) -> R.string.sos_options_shake_no_notifications
            else -> null
        }
        binding.shakeStatus.isVisible = shakeLine != null
        if (shakeLine != null) binding.shakeStatus.setText(shakeLine)
        binding.shakeNotificationsButton.isVisible =
            viewModel.shakeSensorAvailable && shakeOn && !NotificationPermission.isGranted(requireContext())
        // Label and action agree: once Android will not ask again, the button says "Open settings" and opens them.
        binding.shakeNotificationsButton.setText(
            if (notificationsNeedsSettings()) R.string.action_open_settings
            else R.string.sos_options_shake_notifications_action
        )
    }

    private fun notificationsNeedsSettings() = RuntimePermissionAsk.needsSettings(
        askedForNotifications, shouldShowRequestPermissionRationale(NotificationPermission.PERMISSION)
    )

    private fun askForNotifications() {
        if (NotificationPermission.isRuntimePermission() && !notificationsNeedsSettings()) {
            askedForNotifications = true
            notificationLauncher.launch(NotificationPermission.PERMISSION)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)))
        }
    }
}
