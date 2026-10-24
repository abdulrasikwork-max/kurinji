package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentMyReportsBinding
import com.safesphere.app.feature.social.domain.MyReport
import com.safesphere.app.feature.social.domain.ReportTarget

/**
 * Phase 13D: "Your reports" (design §4.6: reporters see the status of their own reports). Only the kind of thing, the
 * reason, the status and a band of when; never the content, the person, or anything about other reporters.
 */
class MyReportsFragment : ViewBindingFragment<FragmentMyReportsBinding>(FragmentMyReportsBinding::inflate) {

    private val viewModel by viewModels<MyReportsViewModel> { MyReportsViewModel.factory(appContainer) }
    private val adapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.reportList.adapter = adapter
        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.load()
    }

    private fun render(state: MyReportsUiState) {
        binding.progress.isVisible = state.isLoading
        adapter.submitList(state.reports.map(::row))
        binding.empty.isVisible = !state.isLoading && state.failure == null && state.reports.isEmpty()
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun row(report: MyReport): PersonRow {
        val what = getString(when (report.target) {
            ReportTarget.POST -> R.string.my_reports_post
            ReportTarget.COMMENT -> R.string.my_reports_perspective
            ReportTarget.PERSON -> R.string.my_reports_person
        })
        val status = getString(when {
            !report.reviewed -> R.string.my_reports_received
            report.actionTaken == true -> R.string.my_reports_action_taken
            report.actionTaken == false -> R.string.my_reports_no_violation
            else -> R.string.my_reports_reviewed
        })
        return PersonRow(
            key = report.reportId,
            title = "$what · ${getString(ModerationLabels.of(report.reason))}",
            subtitle = status + "\n" + getString(TimeBandTexts.of(report.period))
        )
    }
}
