package com.safesphere.app.feature.safety.ui

import com.safesphere.app.R
import com.safesphere.app.core.sms.SmsAvailability

/** What the Ready check reads: the Safety Circle copy on this phone, the SMS opt-in and the permission answers. */
data class ReadyInputs(
    /** False until the phone's Safety Circle copy has been read. */
    val circleKnown: Boolean,
    /** Enabled Safety Circle contacts in the phone's copy (the one used for SMS without internet). */
    val circlePeople: Int,
    val autoSms: Boolean,
    val sms: SmsAvailability,
    val locationAllowed: Boolean,
    /** Null where the phone does not ask (Android 12 and older). */
    val notificationsAllowed: Boolean?
)

enum class ReadyAction { OPEN_SAFETY_CIRCLE, OPEN_APP_SETTINGS }

/** How a row is marked: words always carry the meaning; the icon differs in shape too. */
enum class ReadyMark { SET, CHOICE, NEEDS_ATTENTION }

/**
 * One fact: a string resource, or a plurals resource when [count] is set (only the Safety Circle row). [action] is
 * what the row's button opens; null = no button.
 */
data class ReadyRow(
    val fact: Int,
    val mark: ReadyMark,
    val action: ReadyAction?,
    val count: Int? = null
)

/**
 * Phase 13F (docs/PHASE13F_PLAN.md F12, decision D10): "Before you need it" in You → Safety. Facts about this phone,
 * each with the screen that changes it. It never says the person is ready, safe or protected: it says what will happen
 * (for example "the SMS app opens and you send it") and lets the person decide. Pure, so it is unit-tested.
 */
object ReadyCheck {

    fun rows(inputs: ReadyInputs): List<ReadyRow> = buildList {
        add(when {
            !inputs.circleKnown -> ReadyRow(R.string.ready_circle_unknown, ReadyMark.CHOICE, ReadyAction.OPEN_SAFETY_CIRCLE)
            inputs.circlePeople == 0 ->
                ReadyRow(R.string.ready_circle_empty, ReadyMark.NEEDS_ATTENTION, ReadyAction.OPEN_SAFETY_CIRCLE)
            else -> ReadyRow(R.plurals.ready_circle_people, ReadyMark.SET, ReadyAction.OPEN_SAFETY_CIRCLE, inputs.circlePeople)
        })
        add(when {
            inputs.sms == SmsAvailability.NOT_SUPPORTED -> ReadyRow(R.string.ready_sms_not_supported, ReadyMark.CHOICE, null)
            !inputs.autoSms -> ReadyRow(R.string.ready_sms_off, ReadyMark.CHOICE, ReadyAction.OPEN_SAFETY_CIRCLE)
            inputs.sms == SmsAvailability.PERMISSION_NEEDED ->
                ReadyRow(R.string.ready_sms_permission, ReadyMark.NEEDS_ATTENTION, ReadyAction.OPEN_SAFETY_CIRCLE)
            else -> ReadyRow(R.string.ready_sms_on, ReadyMark.SET, ReadyAction.OPEN_SAFETY_CIRCLE)
        })
        add(if (inputs.locationAllowed) ReadyRow(R.string.ready_location_on, ReadyMark.SET, null)
            else ReadyRow(R.string.ready_location_off, ReadyMark.NEEDS_ATTENTION, ReadyAction.OPEN_APP_SETTINGS))
        when (inputs.notificationsAllowed) {
            true -> add(ReadyRow(R.string.ready_notifications_on, ReadyMark.SET, null))
            false -> add(ReadyRow(R.string.ready_notifications_off, ReadyMark.NEEDS_ATTENTION, ReadyAction.OPEN_APP_SETTINGS))
            null -> Unit
        }
        add(ReadyRow(R.string.ready_shortcut, ReadyMark.CHOICE, null))
    }
}
