package com.safesphere.app.feature.emergency.ui

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentEmergencyHistoryBinding

/** The person's own emergency sessions, newest first. */
class EmergencyHistoryFragment :
    ViewBindingFragment<FragmentEmergencyHistoryBinding>(FragmentEmergencyHistoryBinding::inflate) {

    private val viewModel by viewModels<EmergencyHistoryViewModel> {
        EmergencyHistoryViewModel.factory(appContainer)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        if (viewModel.uiState.value.hasLoaded) viewModel.load()
    }

    private fun render(state: EmergencyHistoryUiState) {
        binding.eventList.adapter = EmergencyEventAdapter(state.events) { event ->
            findNavController().navigate(R.id.emergencyDetailFragment, bundleOf(EmergencyDetailFragment.ARG_EMERGENCY_ID to event.id))
        }

        binding.progress.isVisible = state.isLoading
        binding.eventList.isVisible = state.events.isNotEmpty()
        binding.emptyState.isVisible = state.hasLoaded && state.events.isEmpty() && state.failure == null

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }
}
