package com.safesphere.app.feature.community.ui

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationPermissionStatus
import com.safesphere.app.core.location.LocationPermissions
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentCommunityBinding
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.AreaSignal
import com.safesphere.app.feature.community.domain.CommunityPolicy.ReportReadiness
import com.safesphere.app.feature.community.domain.CommunityStanding
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import java.text.DateFormat
import java.time.LocalTime
import java.util.Date
import com.safesphere.app.feature.community.domain.MyContribution
import com.safesphere.app.feature.community.domain.SignalCategory
import com.safesphere.app.feature.intelligence.domain.IntelligencePolicy
import com.safesphere.app.feature.intelligence.ui.IntelligenceTexts

/**
 * Community Safety (Phase 6): what people report about places around you, and a way to add to it. A calm summary
 * and a radar instead of a feed; no likes, followers, authors, comments or photos. Location is asked only from
 * here, after an explanation, and used only while this screen is open.
 */
class CommunityFragment : ViewBindingFragment<FragmentCommunityBinding>(FragmentCommunityBinding::inflate) {

    private val viewModel by viewModels<CommunityViewModel> { CommunityViewModel.factory(appContainer) }

    private var permanentlyDenied = false

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val status = LocationPermissions.statusAfterRequest(
                fineGranted = result[LocationPermissions.REQUESTED[0]] == true,
                coarseGranted = result[LocationPermissions.REQUESTED[1]] == true,
                shouldShowRationale = shouldShowRequestPermissionRationale(LocationPermissions.REQUESTED[0])
            )
            permanentlyDenied = status == LocationPermissionStatus.PERMANENTLY_DENIED
            viewModel.onPermissionResult()
        }

    private val signalAdapter = AreaSignalAdapter(::confirmAnswer, ::showWhy, ::chooseFlagReason)
    private val myAdapter = MyContributionAdapter(::confirmWithdraw)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.signalList.adapter = signalAdapter
        binding.myList.adapter = myAdapter
        binding.reportButton.setOnClickListener { chooseCategory() }
        collectWhenStarted(viewModel.ui, ::render)

        if (savedInstanceState == null) {
            Motion.revealInSequence(
                listOf(binding.headerBlock, binding.areaSection, binding.reportSection, binding.yourReportsSection)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onScreenVisible()
    }

    private fun render(ui: CommunityUiState) {
        renderArea(ui.area)

        myAdapter.submitList(ui.mine)
        binding.myList.isVisible = ui.mine.isNotEmpty()
        binding.myEmpty.isVisible = ui.mine.isEmpty()

        binding.pendingReports.isVisible = ui.pendingReports > 0
        binding.pendingReports.text = resources.getQuantityString(R.plurals.community_pending, ui.pendingReports, ui.pendingReports)
        binding.reportButton.isEnabled = !ui.busy && ui.area !is AreaState.NeedsPermission &&
            !ui.standing.contributionsPaused
        renderStanding(ui.standing)

        ui.event?.let {
            showEvent(it)
            viewModel.onEventShown()
        }
        if (ui.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun renderArea(area: AreaState) {
        val ready = area as? AreaState.Ready
        renderPicture(ready)
        binding.radar.isVisible = ready != null
        binding.radarLegend.isVisible = ready != null
        binding.signalList.isVisible = ready != null && ready.placed.isNotEmpty()
        binding.areaProgress.isVisible = area is AreaState.Locating || area is AreaState.Loading
        binding.areaHint.isVisible = false
        binding.areaHint.text = ""
        binding.areaActionButton.isVisible = false

        when (area) {
            AreaState.Locating -> binding.areaStatus.setText(R.string.community_locating)
            AreaState.Loading -> binding.areaStatus.setText(R.string.community_loading)
            AreaState.NeedsPermission -> {
                binding.areaStatus.setText(R.string.community_permission_title)
                showHint(R.string.community_permission_rationale)
                if (permanentlyDenied) {
                    showAction(R.string.action_open_settings) { openAppSettings() }
                } else {
                    showAction(R.string.action_allow_location) { locationLauncher.launch(LocationPermissions.REQUESTED) }
                }
            }
            is AreaState.LocationProblem -> {
                binding.areaStatus.setText(locationProblemText(area.reason))
                showAction(R.string.action_try_again) { viewModel.refresh() }
            }
            is AreaState.Failed -> {
                binding.areaStatus.text = area.failure.toMessage(requireContext())
                showAction(R.string.action_try_again) { viewModel.refresh() }
            }
            is AreaState.Ready -> {
                binding.radar.setSignals(area.placed)
                signalAdapter.submitList(area.placed)
                val brief = area.brief
                val summary = if (brief.isEmpty) {
                    getString(R.string.community_brief_empty)
                } else {
                    val parts = mutableListOf<String>()
                    if (brief.incidents > 0) parts += resources.getQuantityString(R.plurals.brief_incidents, brief.incidents, brief.incidents)
                    if (brief.conditions > 0) parts += resources.getQuantityString(R.plurals.brief_conditions, brief.conditions, brief.conditions)
                    if (brief.havens > 0) parts += resources.getQuantityString(R.plurals.brief_havens, brief.havens, brief.havens)
                    getString(R.string.community_brief, parts.joinToString(", "))
                }
                binding.areaStatus.text = summary
                brief.nearestHavenMeters?.let { meters ->
                    showHint(getString(R.string.community_nearest_haven, CommunityTexts.distanceText(requireContext(), meters.toDouble())))
                }
                if (area.approximateLocation) showHint(R.string.community_approximate_location)
                binding.radar.contentDescription = getString(R.string.cd_radar, summary)
                showAction(R.string.action_refresh) { viewModel.refresh() }
            }
        }
    }

    /**
     * Phase 8: the area picture card and the radar's area cells. Shown only for a fresh answer (Ready with a picture);
     * any other state hides both, so an old picture is never shown as current. Night order uses the phone's clock.
     */
    private fun renderPicture(ready: AreaState.Ready?) {
        val picture = ready?.picture
        binding.pictureBlock.isVisible = picture != null
        if (picture == null || ready == null) {
            binding.radar.setAreaCells(emptyList())
            binding.radarLegend.setText(R.string.radar_legend)
            return
        }
        val night = IntelligencePolicy.isNight(LocalTime.now().hour)
        val headline = getString(IntelligenceTexts.headline(picture.outlook))
        val details = IntelligenceTexts.details(requireContext(), picture, ready.origin, night)
        val text = SpannableStringBuilder(headline).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, headline.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            details.forEach { append("\n").append(it) }
        }
        binding.pictureNotice.setWarning(picture.needsCare)
        binding.pictureNotice.setMessage(text)
        binding.pictureBlock.contentDescription = getString(R.string.cd_picture,
            (listOf(headline) + details + getString(R.string.picture_footer)).joinToString(" "))
        binding.radar.setAreaCells(IntelligencePolicy.radarCells(ready.origin, picture))
        binding.radarLegend.text = getString(R.string.radar_legend_two_lines, getString(R.string.radar_legend),
            getString(R.string.radar_legend_cells))
    }

    private fun showHint(textRes: Int) = showHint(getString(textRes))

    /** Adds a line to the hint below the status (renderArea hides and empties it first). */
    private fun showHint(text: String) {
        val current = if (binding.areaHint.isVisible) binding.areaHint.text.toString() else ""
        binding.areaHint.isVisible = true
        binding.areaHint.text = if (current.isBlank()) text else "$current\n$text"
    }

    private fun showAction(textRes: Int, action: () -> Unit) {
        binding.areaActionButton.isVisible = true
        binding.areaActionButton.setText(textRes)
        binding.areaActionButton.setOnClickListener { action() }
    }

    private fun locationProblemText(reason: LocationUnavailableReason) = when (reason) {
        LocationUnavailableReason.LOCATION_DISABLED -> R.string.community_location_off
        LocationUnavailableReason.SERVICES_UNAVAILABLE -> R.string.community_location_unsupported
        LocationUnavailableReason.NO_FIX, LocationUnavailableReason.PERMISSION_DENIED -> R.string.community_no_fix
    }

    // ---- Reporting ---------------------------------------------------------------------------------------------

    private fun chooseCategory() {
        val categories = SignalCategory.entries
        val labels = categories.map { category ->
            val group = getString(CommunityTexts.badge(category.group).second)
            getString(R.string.category_choice, group, getString(CommunityTexts.category(category)))
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.report_choose_title)
            .setItems(labels) { _, which -> confirmReport(categories[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmReport(category: SignalCategory) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(CommunityTexts.category(category))
            .setMessage(R.string.report_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_share_anonymously) { _, _ -> viewModel.onReport(category) }
            .show()
    }

    private fun confirmAnswer(signal: AreaSignal, kind: ContributionKind) {
        val message = if (kind == ContributionKind.CONFIRM) R.string.answer_confirm_message else R.string.answer_no_longer_message
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(CommunityTexts.category(signal.category))
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(if (kind == ContributionKind.CONFIRM) R.string.action_saw_this_too else R.string.action_no_longer_true) { _, _ ->
                viewModel.onAnswer(signal, kind)
            }
            .show()
    }

    /** Phase 7: paused by a moderator. Says what still works, until when, and offers an appeal. */
    private fun renderStanding(standing: CommunityStanding) {
        binding.standingNotice.isVisible = standing.contributionsPaused
        binding.appealButton.isVisible = standing.contributionsPaused && standing.canAppeal
        if (!standing.contributionsPaused) return
        val until = standing.pausedUntilMillis?.let {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
        }
        val main = if (until != null) {
            getString(R.string.community_paused, until, getString(CommunityTexts.pausedReason(standing.reason)))
        } else {
            getString(R.string.community_paused_short)
        }
        val appeal = when (standing.appeal) {
            AppealStatus.OPEN -> "\n" + getString(R.string.community_appeal_open)
            AppealStatus.UPHELD -> "\n" + getString(R.string.community_appeal_upheld)
            else -> ""
        }
        binding.standingNotice.setMessage(main + appeal)
        binding.appealButton.setOnClickListener { viewModel.onAppeal() }
    }

    private fun showWhy(signal: AreaSignal) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.why_title)
            .setMessage(CommunityTexts.why(requireContext(), signal))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseFlagReason(signal: AreaSignal) {
        val reasons = FlagReason.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.flag_choose_title)
            .setItems(reasons.map { getString(CommunityTexts.flagReason(it)) }.toTypedArray()) { _, which ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.flag_confirm_title)
                    .setMessage(R.string.flag_confirm_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_flag_confirm) { _, _ -> viewModel.onFlag(signal, reasons[which]) }
                    .show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmWithdraw(contribution: MyContribution) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.withdraw_title)
            .setMessage(R.string.withdraw_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_withdraw) { _, _ -> viewModel.onWithdrawConfirmed(contribution) }
            .show()
    }

    private fun showEvent(event: CommunityEvent) {
        val text = when (event) {
            is CommunityEvent.Recorded -> resources.getQuantityString(
                R.plurals.community_recorded, event.appearsWithinMinutes, event.appearsWithinMinutes
            )
            CommunityEvent.SavedOffline -> getString(R.string.community_saved_offline)
            is CommunityEvent.OutboxSent -> resources.getQuantityString(R.plurals.community_outbox_sent, event.count, event.count)
            CommunityEvent.Withdrawn -> getString(R.string.community_withdrawn)
            is CommunityEvent.ReportNotReady -> getString(
                if (event.readiness == ReportReadiness.NEEDS_PRECISE_LOCATION) R.string.community_needs_precise
                else R.string.community_location_rough
            )
            is CommunityEvent.NoLocation -> getString(locationProblemText(event.reason))
            is CommunityEvent.Failed -> event.failure.toMessage(requireContext())
            CommunityEvent.Flagged -> getString(R.string.community_flagged)
            CommunityEvent.AppealSent -> getString(R.string.community_appeal_sent)
            CommunityEvent.ContributionsPaused -> getString(R.string.community_paused_short)
        }
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
        )
    }
}
