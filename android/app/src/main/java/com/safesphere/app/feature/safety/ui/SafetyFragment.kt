package com.safesphere.app.feature.safety.ui

import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.components.BadgeStatus
import com.safesphere.app.core.ui.components.FeatureItem
import com.safesphere.app.core.ui.components.FeatureListAdapter
import com.safesphere.app.databinding.FragmentSafetyBinding

/**
 * Safety Center: the safety tools, each honestly labelled. SOS, Safe Walk (with check-ins), Safety Circle,
 * Emergency History and Safety Settings are available.
 */
class SafetyFragment : ViewBindingFragment<FragmentSafetyBinding>(FragmentSafetyBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.featureList.adapter = FeatureListAdapter(featureItems())

        if (savedInstanceState == null) {
            Motion.revealInSequence(listOf(binding.headerBlock, binding.previewNotice, binding.featureList))
        }
    }

    private fun featureItems(): List<FeatureItem> = listOf(
        FeatureItem(
            R.drawable.ic_shield_alert, R.string.feature_sos_title, R.string.feature_sos_description,
            BadgeStatus.AVAILABLE, isEmergency = true
        ) { findNavController().navigate(R.id.emergencyFragment) },
        // Phase 4B: check-ins are part of Safe Walk, so both entries open the same screen.
        FeatureItem(
            R.drawable.ic_route, R.string.feature_safe_walk_title, R.string.feature_safe_walk_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.safeWalkFragment) },
        FeatureItem(
            R.drawable.ic_group, R.string.feature_safety_circle_title, R.string.feature_safety_circle_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.safetyCircleFragment) },
        FeatureItem(
            R.drawable.ic_history, R.string.feature_emergency_history_title, R.string.feature_emergency_history_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.emergencyHistoryFragment) },
        FeatureItem(
            R.drawable.ic_tune, R.string.feature_safety_settings_title, R.string.feature_safety_settings_description,
            BadgeStatus.AVAILABLE
        ) { findNavController().navigate(R.id.action_safety_to_settings) }
    )
}
