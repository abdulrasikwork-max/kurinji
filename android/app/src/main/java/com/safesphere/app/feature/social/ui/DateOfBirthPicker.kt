package com.safesphere.app.feature.social.ui

import androidx.fragment.app.Fragment
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.safesphere.app.R
import com.safesphere.app.feature.social.domain.AgePolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 13A: the date-of-birth picker of sign-up and of the one-time age confirmation. Opens in text-entry mode (typing
 * a birth date is quicker than paging back 18+ years), at the latest 18+ date; future dates cannot be chosen. A
 * MaterialDatePicker survives rotation but loses its listener, so screens call [reattach] in onViewCreated.
 */
object DateOfBirthPicker {

    fun show(fragment: Fragment, tag: String, current: LocalDate?, today: LocalDate, onChosen: (LocalDate) -> Unit) {
        if (fragment.childFragmentManager.findFragmentByTag(tag) != null) return
        val constraints = CalendarConstraints.Builder()
            .setStart(millis(today.minusYears(AgePolicy.MAXIMUM_AGE.toLong())))
            .setEnd(millis(today))
            .setOpenAt(millis(current ?: AgePolicy.latestAdultBirthDate(today)))
            .setValidator(DateValidatorPointBackward.before(millis(today.plusDays(1))))
            .build()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.label_date_of_birth)
            .setInputMode(MaterialDatePicker.INPUT_MODE_TEXT)
            .setSelection(current?.let(::millis))
            .setCalendarConstraints(constraints)
            .build()
        picker.addOnPositiveButtonClickListener { selection -> onChosen(dateOf(selection)) }
        picker.show(fragment.childFragmentManager, tag)
    }

    /** Re-attaches [onChosen] to a picker restored after a configuration change. */
    @Suppress("UNCHECKED_CAST")
    fun reattach(fragment: Fragment, tag: String, onChosen: (LocalDate) -> Unit) {
        val picker = fragment.childFragmentManager.findFragmentByTag(tag) as? MaterialDatePicker<Long> ?: return
        picker.clearOnPositiveButtonClickListeners()
        picker.addOnPositiveButtonClickListener { selection -> onChosen(dateOf(selection)) }
    }

    /** The picker works in UTC milliseconds at midnight. */
    private fun millis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun dateOf(selection: Long): LocalDate = Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()
}
