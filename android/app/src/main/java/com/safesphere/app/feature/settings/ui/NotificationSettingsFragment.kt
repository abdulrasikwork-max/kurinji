package com.safesphere.app.feature.settings.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.push.PushSwitches
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentNotificationSettingsBinding

/**
 * N7 (D31): Settings → Notifications. Replies, Plans, Messages in plans, People (and Community staff for staff) are
 * the server's switches; "Show what people wrote" and "Quiet at night" stay on this phone; the phone's own settings
 * are one tap away. Safety and account notices have no switch here (they come only from something you started).
 */
class NotificationSettingsFragment :
    ViewBindingFragment<FragmentNotificationSettingsBinding>(FragmentNotificationSettingsBinding::inflate) {

    private val viewModel: NotificationSettingsViewModel by viewModels {
        NotificationSettingsViewModel.factory(appContainer)
    }

    private var rendering = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fun server(switch: CompoundButton, transform: (PushSwitches, Boolean) -> PushSwitches) =
            switch.setOnCheckedChangeListener { _, on -> if (!rendering) viewModel.change { transform(it, on) } }
        server(binding.repliesSwitch) { s, on -> s.copy(replies = on) }
        server(binding.plansSwitch) { s, on -> s.copy(plans = on) }
        server(binding.planMessagesSwitch) { s, on -> s.copy(planMessages = on) }
        server(binding.peopleSwitch) { s, on -> s.copy(people = on) }
        server(binding.staffSwitch) { s, on -> s.copy(staff = on) }
        binding.showContentSwitch.setOnCheckedChangeListener { _, on -> if (!rendering) viewModel.setShowContent(on) }
        binding.quietSwitch.setOnCheckedChangeListener { _, on -> if (!rendering) viewModel.setQuietAtNight(on) }
        binding.androidSettingsButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
            }
        }
        collectWhenStarted(viewModel.state, ::render)
        viewModel.start()
    }

    private fun render(state: NotificationSettingsState) {
        rendering = true
        val switches = state.switches
        val serverRows = listOf(binding.repliesSwitch to binding.repliesSwitchDescription,
            binding.plansSwitch to binding.plansSwitchDescription,
            binding.planMessagesSwitch to binding.planMessagesSwitchDescription,
            binding.peopleSwitch to binding.peopleSwitchDescription)
        binding.sendHeader.isVisible = switches != null
        serverRows.forEach { (s, d) -> s.isVisible = switches != null; d.isVisible = switches != null }
        binding.staffSwitch.isVisible = switches != null && state.staff
        binding.staffSwitchDescription.isVisible = binding.staffSwitch.isVisible
        if (switches != null) {
            binding.repliesSwitch.isChecked = switches.replies
            binding.plansSwitch.isChecked = switches.plans
            binding.planMessagesSwitch.isChecked = switches.planMessages
            binding.peopleSwitch.isChecked = switches.people
            binding.staffSwitch.isChecked = switches.staff
            serverRows.forEach { (s, _) -> s.isEnabled = !state.saving }
            binding.staffSwitch.isEnabled = !state.saving
        }
        binding.showContentSwitch.isChecked = state.showContent
        binding.quietSwitch.isChecked = state.quietAtNight
        val message = when {
            !state.configured -> getString(R.string.notifications_unavailable)
            state.problem == NotificationSettingsState.Problem.LOAD -> getString(R.string.notifications_load_failed)
            state.problem == NotificationSettingsState.Problem.SAVE -> getString(R.string.notifications_save_failed)
            else -> null
        }
        binding.notice.isVisible = message != null
        message?.let { binding.notice.setMessage(it) }
        rendering = false
    }
}
