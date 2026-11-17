package com.safesphere.app.feature.moments.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.CompositeDateValidator
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentQuestionsAdminBinding
import com.safesphere.app.feature.moments.domain.QuestionRules
import com.safesphere.app.feature.moments.domain.ScheduledQuestion
import com.safesphere.app.feature.social.ui.PersonRow
import com.safesphere.app.feature.social.ui.PersonRowAdapter
import com.safesphere.app.feature.social.ui.RowAction
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase N4 (A3): "Daily questions" for admins (a row under Community staff in Settings and more, shown from the admin
 * flag only; the server checks the role on every call). Pick an India day (today to 60 days ahead, days that already
 * have one are said to be taken), write 3 to 140 characters on one line, schedule. Delete asks first and says the
 * answers stay. Nothing here is about a person, so the screen is not screenshot-protected.
 */
class QuestionsAdminFragment : ViewBindingFragment<FragmentQuestionsAdminBinding>(FragmentQuestionsAdminBinding::inflate) {

    private val viewModel by viewModels<QuestionsAdminViewModel> { QuestionsAdminViewModel.factory(appContainer) }
    private val adapter = PersonRowAdapter()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.questionList.adapter = adapter
        binding.dayButton.setOnClickListener { chooseDay() }
        binding.textInput.doAfterTextChanged { viewModel.onText(it?.toString().orEmpty()) }
        binding.scheduleButton.setOnClickListener { viewModel.onSchedule() }
        collectWhenStarted(viewModel.uiState, ::render)
        if (savedInstanceState == null) viewModel.load()
    }

    private fun render(state: QuestionsAdminUiState) {
        if (view == null) return
        binding.progress.isVisible = state.loading || state.busy
        binding.formBlock.isVisible = !state.notAllowed && !state.needsMembership
        val problem = when {
            state.needsMembership -> getString(R.string.questions_admin_members_only)
            state.notAllowed -> getString(R.string.questions_admin_not_allowed)
            else -> null
        }
        binding.statusNotice.isVisible = problem != null
        problem?.let { binding.statusNotice.setMessage(it) }

        binding.dayButton.text = state.day?.let { day(it, state.today) } ?: getString(R.string.questions_admin_choose_day)
        binding.dayTaken.isVisible = state.dayTaken
        binding.scheduleButton.isEnabled = state.canSchedule
        if (binding.textInput.text?.toString() != state.text && state.text.isEmpty()) binding.textInput.setText("")

        binding.emptyLine.isVisible = !state.loading && state.questions.isEmpty()
        adapter.submitList(state.questions.map { q ->
            PersonRow(
                key = q.id,
                title = q.text,
                subtitle = day(q.day, state.today),
                primary = RowAction(getString(R.string.questions_admin_delete), getString(R.string.questions_admin_delete_cd, q.text)) {
                    confirmDelete(q)
                }
            )
        })

        if (state.scheduled) {
            viewModel.onScheduledShown()
            Toast.makeText(requireContext(), R.string.questions_admin_scheduled, Toast.LENGTH_SHORT).show()
        }
        state.failure?.let { failure ->
            if (!state.notAllowed && !state.needsMembership) {
                Toast.makeText(requireContext(), failure.toMessage(requireContext()), Toast.LENGTH_LONG).show()
            }
            viewModel.onFailureShown()
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    /** A calendar limited to today .. today + 60 (India days); the chosen date is the India day itself. */
    private fun chooseDay() {
        val today = viewModel.uiState.value.today ?: return
        val first = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val last = today.plusDays(QuestionRules.DAYS_AHEAD).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val selected = (viewModel.uiState.value.day ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val constraints = CalendarConstraints.Builder()
            .setStart(first).setEnd(last).setOpenAt(selected)
            .setValidator(CompositeDateValidator.allOf(listOf(DateValidatorPointForward.from(first),
                DateValidatorPointBackward.before(last + 1))))
            .build()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.questions_admin_choose_day)
            .setSelection(selected)
            .setCalendarConstraints(constraints)
            .build()
        // The picker works in UTC midnights: its date IS the calendar day chosen.
        picker.addOnPositiveButtonClickListener { millis ->
            viewModel.onDay(java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
        }
        picker.show(childFragmentManager, "question_day")
    }

    private fun confirmDelete(question: ScheduledQuestion) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.questions_admin_delete_title)
            .setMessage(R.string.questions_admin_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.questions_admin_delete) { _, _ -> viewModel.onDelete(question) }
            .show()
    }

    private fun day(day: LocalDate, today: LocalDate?): String {
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(day)
        return when (day) {
            today -> getString(R.string.questions_admin_today, date)
            today?.plusDays(1) -> getString(R.string.questions_admin_tomorrow, date)
            else -> date
        }
    }
}
