package com.safesphere.app.feature.membership.ui.staff

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentStaffListBinding
import com.safesphere.app.feature.membership.domain.BanReason
import com.safesphere.app.feature.membership.ui.MembershipLabels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase N3 (A2): one admin list, chosen by [ARG_MODE]: VERIFIERS (appoint / remove by phone number), BANS (ban a phone
 * number and/or a roll number with a reason, lift), PHOTOS (the ID-photo queue, oldest first) or LOG (the community log,
 * 50 lines a page). FLAG_SECURE. No list of verifiers exists anywhere else (owner's item 7).
 */
class StaffListFragment : ViewBindingFragment<FragmentStaffListBinding>(FragmentStaffListBinding::inflate) {

    enum class Mode { VERIFIERS, BANS, PHOTOS, LOG }

    private val viewModel by viewModels<StaffAdminViewModel> { StaffAdminViewModel.factory(appContainer) }
    private val adapter = StaffRowAdapter()
    private var banReason: BanReason? = null
    private val mode: Mode
        get() = Mode.entries.firstOrNull { it.name == arguments?.getString(ARG_MODE) } ?: Mode.LOG

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        banReason = savedInstanceState?.getString(STATE_REASON)?.let { name -> BanReason.entries.firstOrNull { it.name == name } }
        binding.list.adapter = adapter
        setUpForm()
        binding.moreButton.setOnClickListener { viewModel.loadLog(more = true) }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        banReason?.let { outState.putString(STATE_REASON, it.name) }
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    private fun reload() = when (mode) {
        Mode.VERIFIERS -> viewModel.loadVerifiers()
        Mode.BANS -> viewModel.loadBans()
        Mode.PHOTOS -> viewModel.loadQueue()
        Mode.LOG -> viewModel.loadLog()
    }

    private fun setUpForm() {
        val phone = { binding.phoneInput.text?.toString().orEmpty() }
        binding.phoneInput.setOnFocusChangeListener { _, _ -> viewModel.onFieldEdited(StaffField.PHONE) }
        when (mode) {
            Mode.VERIFIERS -> {
                binding.introText.setText(R.string.staff_verifiers_intro)
                binding.listTitle.setText(R.string.staff_verifiers_list)
                binding.formBlock.isVisible = true
                binding.primaryButton.setText(R.string.staff_verifier_add)
                binding.secondaryButton.isVisible = true
                binding.secondaryButton.setText(R.string.staff_verifier_remove)
                binding.primaryButton.setOnClickListener { viewModel.setVerifier(phone(), true) }
                binding.secondaryButton.setOnClickListener { confirm(R.string.staff_verifier_remove_confirm) { viewModel.setVerifier(phone(), false) } }
            }
            Mode.BANS -> {
                binding.introText.setText(R.string.staff_bans_intro)
                binding.listTitle.setText(R.string.staff_bans_list)
                binding.formBlock.isVisible = true
                binding.rollLayout.isVisible = true
                binding.reasonLayout.isVisible = true
                binding.noteLayout.isVisible = true
                binding.primaryButton.setText(R.string.staff_ban_button)
                binding.reasonInput.setOnClickListener { chooseBanReason() }
                binding.primaryButton.setOnClickListener {
                    val reason = banReason
                    if (reason == null) {
                        binding.reasonLayout.error = getString(R.string.staff_ban_reason_needed)
                        return@setOnClickListener
                    }
                    confirm(R.string.staff_ban_confirm) {
                        viewModel.ban(phone(), binding.rollInput.text?.toString().orEmpty(), reason,
                            binding.noteInput.text?.toString().orEmpty())
                    }
                }
            }
            Mode.PHOTOS -> {
                binding.introText.setText(R.string.staff_photos_intro)
                binding.listTitle.setText(R.string.staff_photos_list)
            }
            Mode.LOG -> {
                binding.introText.setText(R.string.staff_log_intro)
                binding.listTitle.setText(R.string.staff_log_list)
            }
        }
    }

    private fun render(state: StaffAdminUiState) {
        binding.progress.isVisible = state.loading || state.busy
        binding.primaryButton.isEnabled = !state.busy
        binding.secondaryButton.isEnabled = !state.busy
        binding.phoneLayout.error = when {
            StaffField.PHONE in state.problems -> getString(R.string.staff_phone_problem)
            StaffField.TARGET in state.problems -> getString(R.string.staff_ban_target_needed)
            else -> null
        }
        binding.rollLayout.error = if (StaffField.ROLL in state.problems) getString(R.string.membership_roll_problem) else null
        binding.reasonInput.setText(banReason?.let { getString(MembershipLabels.ban(it.name)) }.orEmpty())

        val rows = when (mode) {
            Mode.VERIFIERS -> state.verifiers?.map { v ->
                StaffRow(v.phoneHint ?: v.name, v.name,
                    listOfNotNull(v.phoneHint?.let { getString(R.string.staff_phone_hint, it) }, v.since?.let { getString(R.string.staff_since, date(it)) })
                        .joinToString(" · "))
            }
            Mode.BANS -> state.bans?.map { b ->
                val on = listOfNotNull(getString(R.string.staff_ban_on_roll).takeIf { b.onRollNumber },
                    getString(R.string.staff_ban_on_phone).takeIf { b.onPhone }).joinToString(" + ")
                StaffRow(b.id.toString(), getString(MembershipLabels.ban(b.reason)) + " · " + on,
                    listOfNotNull(b.person?.let { getString(R.string.staff_ban_person, it) }, b.note,
                        b.createdAt?.let { getString(R.string.staff_ban_by, b.bannedBy ?: "—", date(it)) },
                        b.liftedAt?.let { getString(R.string.staff_ban_lifted_on, date(it)) }).joinToString("\n"),
                    actionLabel = if (b.liftedAt == null) getString(R.string.staff_ban_lift) else null,
                    onAction = { confirm(R.string.staff_ban_lift_confirm) { viewModel.lift(b.id) } })
            }
            Mode.PHOTOS -> state.queue?.map { r ->
                StaffRow(r.ref, r.name,
                    listOfNotNull(getString(R.string.membership_member_detail, r.programmeLabel, r.graduationYear),
                        r.photoUploadedAt?.let { getString(R.string.staff_photo_sent_on, date(it)) }).joinToString("\n"),
                    onOpen = { findNavController().navigate(R.id.staffDecideFragment, DecideFragment.photoArgs(r.ref)) })
            }
            Mode.LOG -> state.log.mapIndexed { i, line ->
                val action = StaffLabels.action(line.action)?.let(::getString) ?: line.action
                StaffRow("$i", listOfNotNull(action, line.subject).joinToString(" · "),
                    listOfNotNull(line.reason?.let { StaffLabels.reason(it)?.let(::getString) ?: it }, line.detail?.let { getString(R.string.staff_log_was, it) },
                        line.actor?.let { getString(R.string.staff_log_by, it) } ?: getString(R.string.staff_log_automatic),
                        line.at?.let(::date)).joinToString(" · "))
            }
        }
        adapter.submit(rows.orEmpty())
        binding.emptyText.isVisible = rows != null && rows.isEmpty() && !state.loading
        binding.emptyText.setText(when (mode) {
            Mode.VERIFIERS -> R.string.staff_verifiers_empty
            Mode.BANS -> R.string.staff_bans_empty
            Mode.PHOTOS -> R.string.staff_photos_empty
            Mode.LOG -> R.string.staff_log_empty
        })
        binding.moreButton.isVisible = mode == Mode.LOG && state.log.isNotEmpty() && !state.logEnded

        state.done?.let { done ->
            Toast.makeText(requireContext(), when (done) {
                StaffDone.VERIFIER_ADDED -> R.string.staff_done_verifier_added
                StaffDone.VERIFIER_REMOVED -> R.string.staff_done_verifier_removed
                StaffDone.BANNED -> R.string.staff_done_banned
                StaffDone.LIFTED -> R.string.staff_done_lifted
                StaffDone.ENDED -> R.string.staff_done_ended
                StaffDone.RELINKED -> R.string.staff_done_relinked
                StaffDone.NAME_CORRECTED -> R.string.staff_done_name
            }, Toast.LENGTH_LONG).show()
            if (done == StaffDone.BANNED || done == StaffDone.VERIFIER_ADDED || done == StaffDone.VERIFIER_REMOVED) {
                binding.phoneInput.setText("")
                binding.rollInput.setText("")
                binding.noteInput.setText("")
                banReason = null
                binding.reasonInput.setText("")
            }
            viewModel.onDoneShown()
        }
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun chooseBanReason() {
        val reasons = BanReason.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.staff_ban_reason)
            .setSingleChoiceItems(reasons.map { getString(MembershipLabels.ban(it.name)) }.toTypedArray(),
                reasons.indexOf(banReason)) { dialog, which ->
                banReason = reasons[which]
                binding.reasonLayout.error = null
                binding.reasonInput.setText(getString(MembershipLabels.ban(reasons[which].name)))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirm(message: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(R.string.staff_confirm) { _, _ -> action() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun date(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(instant.atZone(ZoneId.systemDefault()))

    companion object {
        const val ARG_MODE = "mode"
        private const val STATE_REASON = "banReason"

        /** [title] becomes the toolbar title (the destination's label is "{title}"). */
        fun args(mode: Mode, title: String) = bundleOf(ARG_MODE to mode.name, ARG_TITLE to title)

        private const val ARG_TITLE = "title"
    }
}
