package com.safesphere.app.feature.safety.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import com.google.android.material.color.MaterialColors
import com.safesphere.app.databinding.ItemReadyRowBinding
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.feature.emergency.domain.AutoAudioChoice
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Phase 13F: You → Safety, the hub of the safety tools (docs/PHASE13F_PLAN.md F7): the factual status card and the SOS
 * tile (both from the old Home), then Safe Walk (with check-ins and "Check the way"), the Safety Circle, the Evidence
 * Vault, Nearby heads-ups, emergency history and the safety settings. Every row opens a working screen.
 */
class SafetyFragment : ViewBindingFragment<FragmentSafetyBinding>(FragmentSafetyBinding::inflate) {

    /** The same instance as the banner host's (MainActivity), so the card and the banners never disagree. */
    private val session by activityViewModels<SessionStatusViewModel> { SessionStatusViewModel.factory(appContainer) }
    private val ready by viewModels<ReadyCheckViewModel> { ReadyCheckViewModel.factory(appContainer) }

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
        collectWhenStarted(ready.rows, ::renderReady)

        if (savedInstanceState == null) {
            Motion.revealInSequence(listOf(binding.headerBlock, binding.statusCard, binding.sosTile, binding.featureList))
        }
    }

    // ---- Phase 17F: the one-time question about audio in an SOS -------------------------------------------------

    private val sosOptions by viewModels<SosOptionsViewModel> { SosOptionsViewModel.factory(appContainer) }
    private var audioQuestionShown = false

    private val microphoneLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Asked here, in context, with the question on screen. A refusal is respected: audio stays off.
        sosOptions.setAutoAudio(if (granted) AutoAudioChoice.ON else AutoAudioChoice.OFF)
        if (!granted && isAdded) {
            Toast.makeText(requireContext(), R.string.sos_options_audio_denied, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Asked the first time this account opens You → Safety: may IRL record audio by itself when an SOS is on?
     * It never stands between a person and SOS: it is NOT shown while an SOS or a Safe Walk is running, and Back or a tap
     * outside dismisses it as "ask me next time" (nothing is stored, the choice stays NOT_ASKED). "No" and "Yes" are the two
     * explicit answers; the choice can be changed any time in SOS options. Nothing is recorded by agreeing; the microphone
     * permission is asked only on "Yes".
     */
    private fun askAudioQuestionIfDue() {
        if (audioQuestionShown) return
        // An SOS or a walk already running is never interrupted by a question.
        if (appContainer.sosRecorder.state.value.current != null || appContainer.safeWalk.state.value.isActive) return
        viewLifecycleOwner.lifecycleScope.launch {
            if (!sosOptions.audioQuestionDue() || audioQuestionShown) return@launch
            audioQuestionShown = true
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.audio_question_title)
                .setMessage(R.string.audio_question_message)
                .setNegativeButton(R.string.audio_question_no) { _, _ -> sosOptions.setAutoAudio(AutoAudioChoice.OFF) }
                .setPositiveButton(R.string.audio_question_yes) { _, _ ->
                    val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) sosOptions.setAutoAudio(AutoAudioChoice.ON)
                    else microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        askAudioQuestionIfDue()
        session.onScreenVisible()
        // Back from Settings or the Safety Circle: read the answers again.
        val context = requireContext()
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        ready.refresh(
            locationAllowed = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            notificationsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                granted(Manifest.permission.POST_NOTIFICATIONS) else null
        )
    }

    /** Phase 13F (F12): the Ready check, one row per fact; the icon's shape and the spoken mark carry the state too. */
    private fun renderReady(rows: List<ReadyRow>) {
        binding.readyCard.isVisible = rows.isNotEmpty()
        val list = binding.readyList
        list.removeAllViews()
        val inflater = LayoutInflater.from(list.context)
        for (row in rows) {
            val item = ItemReadyRowBinding.inflate(inflater, list, false)
            val fact = if (row.count != null) resources.getQuantityString(row.fact, row.count, row.count) else getString(row.fact)
            item.fact.text = fact
            val (icon, tint, mark) = when (row.mark) {
                ReadyMark.SET -> Triple(R.drawable.ic_check_circle, androidx.appcompat.R.attr.colorPrimary, R.string.cd_ready_set)
                ReadyMark.CHOICE -> Triple(R.drawable.ic_info, com.google.android.material.R.attr.colorOnSurfaceVariant,
                    R.string.cd_ready_choice)
                ReadyMark.NEEDS_ATTENTION -> Triple(R.drawable.ic_warning, R.attr.colorCaution, R.string.cd_ready_attention)
            }
            item.icon.setImageResource(icon)
            item.icon.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(item.icon, tint))
            item.root.contentDescription = getString(mark) + ". " + fact
            item.root.isFocusable = true
            val action = row.action
            item.action.isVisible = action != null
            if (action != null) {
                item.action.setText(when (action) {
                    ReadyAction.OPEN_SAFETY_CIRCLE -> R.string.ready_action_circle
                    ReadyAction.OPEN_APP_SETTINGS -> R.string.ready_action_settings
                })
                item.action.setOnClickListener {
                    when (action) {
                        ReadyAction.OPEN_SAFETY_CIRCLE -> findNavController().navigate(R.id.safetyCircleFragment)
                        ReadyAction.OPEN_APP_SETTINGS -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", requireContext().packageName, null)))
                    }
                }
            }
            list.addView(item.root)
        }
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
            // Phase 17F: shake to start SOS and audio in an SOS - both off until the person turns them on.
            FeatureItem(R.drawable.ic_tune, R.string.sos_options_title, R.string.sos_options_hub_description) {
                nav.navigate(R.id.sosOptionsFragment)
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
