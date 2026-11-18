package com.safesphere.app.feature.safety.ui

import com.safesphere.app.R
import com.safesphere.app.feature.circlealert.domain.AlertChoice

/** What the Ready check reads: the Safety Circle copy on this phone, the alert choice and the permission answers. */
data class ReadyInputs(
    /** False until the phone's Safety Circle copy has been read. */
    val circleKnown: Boolean,
    /** Enabled Safety Circle contacts in the phone's copy (the one used for texting by hand without internet). */
    val circlePeople: Int,
    /** N6: the person's choice about IRL's automatic texts; null = not known (offline, not read yet). */
    val alertChoice: AlertChoice?,
    val locationAllowed: Boolean,
    /** Null where the phone does not ask (Android 12 and older). */
    val notificationsAllowed: Boolean?
)

/** N6: location and notifications are ASKED from here, ahead of time, so the SOS countdown never has to ask. */
enum class ReadyAction { OPEN_SAFETY_CIRCLE, ASK_LOCATION, ASK_NOTIFICATIONS }

/** How a row is marked: words always carry the meaning; the icon differs in shape too. */
enum class ReadyMark { SET, CHOICE, NEEDS_ATTENTION }

/**
 * One fact: a string resource, or a plurals resource when [count] is set (only the Safety Circle row). [action] is
 * what the row's button does; null = no button.
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
 * and lets the person decide. Pure, so it is unit-tested. N6: the automatic-text row is about the SERVER's texts (the
 * phone never texts by itself); location and notifications are asked here, in context.
 */
object ReadyCheck {

    fun rows(inputs: ReadyInputs): List<ReadyRow> = buildList {
        add(when {
            !inputs.circleKnown -> ReadyRow(R.string.ready_circle_unknown, ReadyMark.CHOICE, ReadyAction.OPEN_SAFETY_CIRCLE)
            inputs.circlePeople == 0 ->
                ReadyRow(R.string.ready_circle_empty, ReadyMark.NEEDS_ATTENTION, ReadyAction.OPEN_SAFETY_CIRCLE)
            else -> ReadyRow(R.plurals.ready_circle_people, ReadyMark.SET, ReadyAction.OPEN_SAFETY_CIRCLE, inputs.circlePeople)
        })
        if (inputs.circlePeople > 0) add(when (inputs.alertChoice) {
            AlertChoice.ON -> ReadyRow(R.string.ready_alerts_on, ReadyMark.SET, ReadyAction.OPEN_SAFETY_CIRCLE)
            AlertChoice.OFF -> ReadyRow(R.string.ready_alerts_off, ReadyMark.CHOICE, ReadyAction.OPEN_SAFETY_CIRCLE)
            AlertChoice.UNSET -> ReadyRow(R.string.ready_alerts_unset, ReadyMark.NEEDS_ATTENTION, ReadyAction.OPEN_SAFETY_CIRCLE)
            null -> ReadyRow(R.string.ready_alerts_unknown, ReadyMark.CHOICE, ReadyAction.OPEN_SAFETY_CIRCLE)
        })
        add(if (inputs.locationAllowed) ReadyRow(R.string.ready_location_on, ReadyMark.SET, null)
            else ReadyRow(R.string.ready_location_off, ReadyMark.NEEDS_ATTENTION, ReadyAction.ASK_LOCATION))
        when (inputs.notificationsAllowed) {
            true -> add(ReadyRow(R.string.ready_notifications_on, ReadyMark.SET, null))
            false -> add(ReadyRow(R.string.ready_notifications_off, ReadyMark.NEEDS_ATTENTION, ReadyAction.ASK_NOTIFICATIONS))
            null -> Unit
        }
        add(ReadyRow(R.string.ready_shortcut, ReadyMark.CHOICE, null))
    }
}
