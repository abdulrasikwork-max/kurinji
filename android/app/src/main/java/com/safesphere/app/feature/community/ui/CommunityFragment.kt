package com.safesphere.app.feature.community.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationNeed
import com.safesphere.app.core.location.LocationNeeds
import com.safesphere.app.core.location.LocationUnavailableReason
import com.safesphere.app.core.ui.LocationAsk
import com.safesphere.app.core.ui.Motion
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentCommunityBinding
import com.safesphere.app.feature.community.domain.AppealStatus
import com.safesphere.app.feature.community.domain.AreaSignal
import com.safesphere.app.feature.community.domain.CommunityPolicy
import com.safesphere.app.feature.community.domain.CommunityPolicy.ReportReadiness
import com.safesphere.app.feature.community.domain.CommunityStanding
import com.safesphere.app.feature.community.domain.ContributionKind
import com.safesphere.app.feature.community.domain.FlagReason
import com.safesphere.app.feature.community.domain.ReportChoices
import com.safesphere.app.databinding.DialogReportSheetBinding
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.view.ViewCompat
import android.widget.TextView
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

    /** Phase 17D: the one way to ask for location, to open settings and to upgrade to precise (shared with other screens). */
    private val locationAsk = LocationAsk(this) { viewModel.onPermissionResult() }

    /** What "Try again" repeats after a position that was too rough (lost on rotation: the dialog then just closes). */
    private var retryAction: (() -> Unit)? = null

    private val signalAdapter = AreaSignalAdapter(::confirmAnswer, ::showWhy, ::chooseFlagReason)
    private val myAdapter = MyContributionAdapter(::confirmWithdraw)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.signalList.adapter = signalAdapter
        binding.myList.adapter = myAdapter
        binding.reportButton.setOnClickListener { onReportTapped() }
        binding.locatingCancelButton.setOnClickListener { viewModel.onCancelLocating() }
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

    override fun onStop() {
        super.onStop()
        viewModel.onScreenHidden() // location only while this screen is open
    }

    /** The location action that would help, from what the phone says now and what the permission dialog answered. */
    private fun locationNeed(wantsPrecise: Boolean): LocationNeed = LocationNeeds.of(
        access = viewModel.locationAccess(),
        locationEnabled = viewModel.locationEnabled(),
        wantsPrecise = wantsPrecise,
        permanentlyDenied = locationAsk.permanentlyDenied,
        preciseDeclined = locationAsk.preciseDeclined
    )

    private fun render(ui: CommunityUiState) {
        renderArea(ui.area)

        val locating = ui.locating
        binding.locatingBlock.isVisible = locating != null
        if (locating != null) {
            val accuracy = locating.accuracyMeters
            binding.locatingText.text = if (accuracy == null) getString(R.string.community_locating_precise)
            else getString(R.string.community_locating_precise_accuracy, accuracy.toInt().coerceAtLeast(1))
        }

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
        binding.areaSecondaryButton.isVisible = false

        when (area) {
            AreaState.Locating -> binding.areaStatus.setText(R.string.community_locating)
            AreaState.Loading -> binding.areaStatus.setText(R.string.community_loading)
            AreaState.NeedsPermission -> {
                binding.areaStatus.setText(R.string.community_permission_title)
                showHint(if (locationAsk.permanentlyDenied) R.string.community_permission_blocked
                    else R.string.community_permission_rationale)
                if (locationAsk.permanentlyDenied) {
                    showAction(R.string.action_open_settings) { locationAsk.act(LocationNeed.OPEN_APP_SETTINGS) }
                } else {
                    showAction(R.string.action_allow_location) { locationAsk.act(LocationNeed.ALLOW) }
                }
            }
            is AreaState.LocationProblem -> {
                binding.areaStatus.setText(locationProblemText(area.reason))
                // Location off: the switch itself (coming back refreshes the view); no signal: wait or move, then retry.
                if (area.reason == LocationUnavailableReason.LOCATION_DISABLED) {
                    showAction(R.string.location_action_turn_on) { locationAsk.act(LocationNeed.TURN_ON) }
                } else if (area.reason != LocationUnavailableReason.SERVICES_UNAVAILABLE) {
                    showAction(R.string.action_try_again) { viewModel.refresh() }
                }
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
                    val parts = CommunityTexts.briefParts(brief).map { (plural, count) ->
                        resources.getQuantityString(plural, count, count)
                    }
                    getString(R.string.community_brief, parts.joinToString(", "))
                }
                binding.areaStatus.text = summary
                brief.nearestHavenMeters?.let { meters ->
                    showHint(getString(R.string.community_nearest_haven, CommunityTexts.distanceText(requireContext(), meters.toDouble())))
                }
                if (area.approximateLocation) {
                    showHint(R.string.community_approximate_location)
                } else if (CommunityPolicy.isRough(area.accuracyMeters)) {
                    // Phase 17D: precise permission, but this particular position is rough (Wi-Fi / cell, indoors).
                    val accuracy = area.accuracyMeters
                    showHint(if (accuracy == null) getString(R.string.community_rough_position_unknown)
                        else getString(R.string.community_rough_position, accuracy.toInt().coerceAtLeast(1)))
                }
                val loadedAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(area.loadedAtMillis))
                showHint(getString(
                    if (CommunityPolicy.isStale(area.loadedAtMillis, System.currentTimeMillis())) R.string.community_stale
                    else R.string.community_updated, loadedAt))
                binding.radar.contentDescription = getString(R.string.cd_radar, summary)
                showAction(R.string.action_refresh) { viewModel.refresh() }
                // Approximate permission: looking works, adding does not. One tap to the upgrade.
                if (area.approximateLocation) {
                    binding.areaSecondaryButton.isVisible = true
                    binding.areaSecondaryButton.setText(upgradeLabel())
                    binding.areaSecondaryButton.setOnClickListener { locationAsk.act(locationNeed(wantsPrecise = true)) }
                }
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

    private fun upgradeLabel(): Int =
        if (locationNeed(wantsPrecise = true) == LocationNeed.UPGRADE_PRECISE_IN_SETTINGS) R.string.location_action_settings
        else R.string.location_action_precise

    /**
     * Phase 17D: before anyone fills in a report, check that location can do it. Approximate-only, switched off or
     * blocked each get their one action here, so nobody chooses a category and then gets refused.
     */
    private fun onReportTapped() {
        val need = locationNeed(wantsPrecise = true)
        if (need == LocationNeed.NONE) showReportSheet() else showNeedDialog(need)
    }

    private fun showNeedDialog(need: LocationNeed) {
        val (message, label) = when (need) {
            LocationNeed.UPGRADE_PRECISE -> R.string.community_needs_precise to R.string.location_action_precise
            LocationNeed.UPGRADE_PRECISE_IN_SETTINGS -> R.string.community_needs_precise_settings to R.string.location_action_settings
            LocationNeed.TURN_ON -> R.string.community_location_off_report to R.string.location_action_turn_on
            LocationNeed.OPEN_APP_SETTINGS -> R.string.community_permission_blocked to R.string.location_action_settings
            else -> R.string.community_permission_rationale to R.string.action_allow_location
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.community_needs_precise_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(label) { _, _ -> locationAsk.act(need) }
            .show()
    }

    /** The position was too rough to place anything: say how rough, what to do, and offer the same action again. */
    private fun showRoughDialog(accuracyMeters: Float?) {
        val message = if (accuracyMeters == null) getString(R.string.community_rough_message_unknown)
        else getString(R.string.community_rough_message, accuracyMeters.toInt().coerceAtLeast(1))
        val retry = retryAction
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.community_rough_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .apply { if (retry != null) setPositiveButton(R.string.action_try_again) { _, _ -> retry() } }
            .show()
    }

    /**
     * Phase 17F (D-9): the whole report in one step. The closed list of place conditions (grouped as on the radar), the
     * privacy text that used to be a second dialog, and "Share anonymously", which stays off until something is chosen.
     * No free text and no photo, by design.
     */
    private fun showReportSheet() {
        val content = DialogReportSheetBinding.inflate(layoutInflater)
        var chosen: SignalCategory? = null
        val choices = content.reportChoices
        ReportChoices.groups.forEach { (group, categories) ->
            choices.addView(TextView(requireContext(), null, 0, R.style.TextAppearance_SafeSphere_Overline).apply {
                text = getString(CommunityTexts.badge(group).second)
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_md), 0, 0)
                ViewCompat.setAccessibilityHeading(this, true)
            })
            categories.forEach { category ->
                choices.addView(MaterialRadioButton(requireContext()).apply {
                    id = View.generateViewId()
                    text = getString(CommunityTexts.category(category))
                    minHeight = resources.getDimensionPixelSize(R.dimen.min_touch_target)
                    tag = category
                })
            }
        }
        // 17F-V5: a bottom sheet whose footer (Share, Cancel) is outside the scrolling choices, so neither button can be
        // clipped at 2.0x text. Back and a swipe down both cancel; nothing is sent until Share is tapped.
        val dialog = BottomSheetDialog(requireContext())
        ViewCompat.setAccessibilityHeading(content.reportTitle, true)
        choices.setOnCheckedChangeListener { group, checkedId ->
            chosen = group.findViewById<View>(checkedId)?.tag as? SignalCategory
            content.reportShareButton.isEnabled = chosen != null
        }
        content.reportCancelButton.setOnClickListener { dialog.dismiss() }
        content.reportShareButton.setOnClickListener {
            val category = chosen ?: return@setOnClickListener
            dialog.dismiss()
            retryAction = { viewModel.onReport(category) }
            viewModel.onReport(category)
        }
        dialog.setContentView(content.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private fun confirmAnswer(signal: AreaSignal, kind: ContributionKind) {
        val message = if (kind == ContributionKind.CONFIRM) R.string.answer_confirm_message else R.string.answer_no_longer_message
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(CommunityTexts.category(signal.category))
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(if (kind == ContributionKind.CONFIRM) R.string.action_saw_this_too else R.string.action_no_longer_true) { _, _ ->
                retryAction = { viewModel.onAnswer(signal, kind) }
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
                    .setPositiveButton(R.string.action_flag_confirm) { _, _ ->
                        retryAction = { viewModel.onFlag(signal, reasons[which]) }
                        viewModel.onFlag(signal, reasons[which])
                    }
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
        // The two events that need a decision are dialogs with a button, not a toast that disappears.
        if (event is CommunityEvent.ReportNotReady) {
            if (event.readiness == ReportReadiness.NEEDS_PRECISE_LOCATION) {
                showNeedDialog(locationNeed(wantsPrecise = true).takeIf { it != LocationNeed.NONE } ?: LocationNeed.UPGRADE_PRECISE)
            } else {
                showRoughDialog(event.accuracyMeters)
            }
            return
        }
        val text = when (event) {
            is CommunityEvent.Recorded -> CommunityTexts.recordedDelay(event.appearsWithinMinutes).let { (plural, count) ->
                resources.getQuantityString(plural, count, count)
            }
            CommunityEvent.SavedOffline -> getString(R.string.community_saved_offline)
            is CommunityEvent.OutboxSent -> resources.getQuantityString(R.plurals.community_outbox_sent, event.count, event.count)
            CommunityEvent.Withdrawn -> getString(R.string.community_withdrawn)
            is CommunityEvent.ReportNotReady -> return // handled above
            CommunityEvent.SearchStopped -> getString(R.string.community_search_stopped)
            is CommunityEvent.NoLocation -> getString(locationProblemText(event.reason))
            is CommunityEvent.Failed -> event.failure.toMessage(requireContext())
            CommunityEvent.Flagged -> getString(R.string.community_flagged)
            CommunityEvent.AppealSent -> getString(R.string.community_appeal_sent)
            CommunityEvent.ContributionsPaused -> getString(R.string.community_paused_short)
        }
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }
}
