package com.safesphere.app.feature.safety.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.notifications.NotificationPermission
import com.safesphere.app.core.ui.RuntimePermissionAsk
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentSosOptionsBinding
import com.safesphere.app.feature.emergency.domain.AutoAudioChoice

/**
 * Phase 17F (D-4): You → Safety → SOS options. Two switches, both off until the person turns them on:
 *  - shake to start the SOS countdown (never starts anything without that cancellable countdown);
 *  - record audio automatically once an SOS is on (the microphone permission is asked HERE, at the moment of the
 *    choice, never during an SOS).
 * Voice is not available and the screen says so.
 */
class SosOptionsFragment : ViewBindingFragment<FragmentSosOptionsBinding>(FragmentSosOptionsBinding::inflate) {

    private val viewModel by viewModels<SosOptionsViewModel> { SosOptionsViewModel.factory(appContainer) }

    private var askedForMicrophone = false
    private var askedForNotifications = false

    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setAutoAudio(AutoAudioChoice.ON)
        } else {
            viewModel.setAutoAudio(AutoAudioChoice.OFF)
            if (isAdded) Toast.makeText(requireContext(), R.string.sos_options_audio_denied, Toast.LENGTH_LONG).show()
        }
        render()
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.shakeSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) viewModel.setShake(checked)
        }
        binding.audioSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (!checked) {
                viewModel.setAutoAudio(AutoAudioChoice.OFF)
            } else if (microphoneGranted()) {
                viewModel.setAutoAudio(AutoAudioChoice.ON)
            } else {
                // Asked here, in context, with the explanation on screen - never during an SOS.
                askedForMicrophone = true
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.shakeNotificationsButton.setOnClickListener { askForNotifications() }
        binding.audioPermissionButton.setOnClickListener {
            if (microphoneNeedsSettings()) {
                openAppSettings()
            } else {
                askedForMicrophone = true
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        collectWhenStarted(viewModel.options) { render() }
    }

    override fun onStart() {
        super.onStart()
        render() // back from Settings: the permissions may have changed
    }

    private fun render() {
        if (view == null) return
        val options = viewModel.options.value
        val shakeSwitch = binding.shakeSwitch
        val audioSwitch = binding.audioSwitch

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

        val audioOn = options?.autoAudio == AutoAudioChoice.ON
        audioSwitch.isEnabled = options != null
        if (audioSwitch.isChecked != audioOn) audioSwitch.isChecked = audioOn
        val needsMicrophone = audioOn && !microphoneGranted()
        binding.audioStatus.isVisible = needsMicrophone
        if (needsMicrophone) binding.audioStatus.setText(R.string.sos_options_audio_no_permission)
        binding.audioPermissionButton.isVisible = needsMicrophone
        binding.audioPermissionButton.setText(
            if (microphoneNeedsSettings()) R.string.action_open_settings else R.string.action_allow_microphone
        )
    }

    private fun microphoneNeedsSettings() = RuntimePermissionAsk.needsSettings(
        askedForMicrophone, shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    )

    private fun notificationsNeedsSettings() = RuntimePermissionAsk.needsSettings(
        askedForNotifications, shouldShowRequestPermissionRationale(NotificationPermission.PERMISSION)
    )

    private fun microphoneGranted() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
