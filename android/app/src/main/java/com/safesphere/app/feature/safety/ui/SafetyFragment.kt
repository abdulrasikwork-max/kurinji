package com.safesphere.app.feature.safety.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.safesphere.app.feature.emergency.entry.SosTileService
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.components.FeatureItem
import com.safesphere.app.core.ui.components.FeatureListAdapter
import com.safesphere.app.databinding.FragmentSafetyBinding

/**
 * Phase 13F: You → Safety, the hub of the safety tools (docs/PHASE13F_PLAN.md F7): the factual status card and the SOS
 * tile (both from the old Home), then Safe Walk (with check-ins and "Check the way"), the Safety Circle, the Evidence
 * Vault, Nearby heads-ups, emergency history and the safety settings. Every row opens a working screen.
 */
class SafetyFragment : ViewBindingFragment<FragmentSafetyBinding>(FragmentSafetyBinding::inflate) {

    /** The same instance as the banner host's (MainActivity), so the card and the banners never disagree. */
    private val session by activityViewModels<SessionStatusViewModel> { SessionStatusViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // SOS opens the SOS screen (cancellable countdown); the Safety Circle is texted only with automatic SMS on.
        binding.sosTile.setOnClickListener { findNavController().navigate(R.id.emergencyFragment) }
        binding.featureList.adapter = FeatureListAdapter(featureItems())
        collectWhenStarted(session.status) { status ->
            val content = SessionStatusCard.contentFor(status)
            binding.statusCard.isVisible = content != null
            if (content != null) binding.statusCard.setContent(content)
        }

        if (savedInstanceState == null) {
            Motion.revealInSequence(listOf(binding.headerBlock, binding.statusCard, binding.sosTile, binding.featureList))
        }
    }

    override fun onStart() {
        super.onStart()
        session.onScreenVisible()
    }

    private fun featureItems(): List<FeatureItem> {
        val nav = findNavController()
        return listOf(
            // Phase 4B: check-ins are part of Safe Walk; "Check the way" is on its set-up screen.
            FeatureItem(R.drawable.ic_route, R.string.feature_safe_walk_title, R.string.safety_hub_walk_description) {
                nav.navigate(R.id.safeWalkFragment)
            },
            FeatureItem(R.drawable.ic_group, R.string.feature_safety_circle_title, R.string.feature_safety_circle_description) {
                nav.navigate(R.id.safetyCircleFragment)
            },
            FeatureItem(R.drawable.ic_lock, R.string.feature_evidence_vault_title, R.string.feature_evidence_vault_description) {
                nav.navigate(R.id.evidenceVaultFragment)
            },
            FeatureItem(R.drawable.ic_community_outline, R.string.safety_hub_nearby_title, R.string.safety_hub_nearby_description) {
                nav.navigate(R.id.communityFragment)
            },
            FeatureItem(R.drawable.ic_history, R.string.feature_emergency_history_title,
                R.string.feature_emergency_history_description) { nav.navigate(R.id.emergencyHistoryFragment) },
            FeatureItem(R.drawable.ic_tune, R.string.feature_safety_settings_title, R.string.feature_safety_settings_description) {
                nav.navigate(R.id.action_safety_to_settings)
            }
        ) + listOfNotNull(
            // Phase 13F (F11): Android 13+ can ask to add the SOS tile directly; older phones add it by editing Quick Settings.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) FeatureItem(R.drawable.ic_shield_alert,
                R.string.safety_hub_tile_title, R.string.safety_hub_tile_description) { requestSosTile() } else null
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestSosTile() {
        val context = requireContext()
        val statusBar = context.getSystemService(StatusBarManager::class.java) ?: return
        statusBar.requestAddTileService(
            ComponentName(context, SosTileService::class.java),
            getString(R.string.sos_tile_label),
            Icon.createWithResource(context, R.drawable.ic_shield_alert),
            ContextCompat.getMainExecutor(context)
        ) { result ->
            if (!isAdded) return@requestAddTileService
            val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            Toast.makeText(context, if (added) R.string.safety_hub_tile_added else R.string.safety_hub_tile_not_added,
                Toast.LENGTH_LONG).show()
        }
    }
}
