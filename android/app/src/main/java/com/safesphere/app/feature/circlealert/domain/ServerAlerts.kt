package com.safesphere.app.feature.circlealert.domain

import com.safesphere.app.core.network.ApiResult
import java.time.Instant

/**
 * N6 (D20): what the SERVER does for the Safety Circle. The person chooses once whether IRL texts their enabled contacts
 * when an SOS reaches the server; the phone only shows the choice, the exact text and, during an SOS, what happened per
 * contact. Every unknown value from the server is read fail-closed: as "not confirmed", never as "texted".
 */
enum class AlertChoice { UNSET, ON, OFF }

/** What IRL would do for one enabled contact: text them automatically, or not (then the person texts them by hand). */
data class ContactAlertPlan(val contactId: Long, val name: String, val automatic: Boolean, val reason: SkipCause?)

/** Why IRL will not (or did not) text a contact. [OTHER] = a reason this app does not know yet. */
enum class SkipCause {
    NOT_INDIAN_MOBILE, NOT_IN_FIRST_FIVE, DUPLICATE_NUMBER, NOT_AVAILABLE, NUMBER_LIMIT, PERSON_LIMIT, SERVER_LIMIT,
    TOO_LATE, ENDED, CONTACT_GONE, OTHER;

    companion object {
        fun of(raw: String?): SkipCause? = raw?.let { r -> entries.firstOrNull { it.name == r } ?: OTHER }
    }
}

/** The state of one server text. [UNKNOWN] = anything this app does not know: shown as "not confirmed". */
enum class ServerAlertStatus {
    QUEUED, SENT, DELIVERED, FAILED, SKIPPED, UNKNOWN;

    companion object {
        fun of(raw: String?): ServerAlertStatus = entries.firstOrNull { it.name == raw && it != UNKNOWN } ?: UNKNOWN
    }
}

/** What the server text was about. [OTHER] = unknown to this app (shown as a plain "text"). */
enum class ServerAlertKind {
    SOS_STARTED, SOS_LOCATION, SOS_RESOLVED, SOS_CANCELLED, OTHER;

    companion object {
        fun of(raw: String?): ServerAlertKind = entries.firstOrNull { it.name == raw && it != OTHER } ?: OTHER
    }
}

data class ServerAlert(
    val contactId: Long?,
    val contactName: String?,
    val kind: ServerAlertKind,
    val status: ServerAlertStatus,
    val reason: SkipCause?,
    val at: Instant?,
    val late: Boolean
)

data class TestAlert(val status: ServerAlertStatus, val at: Instant?)

data class AlertSettings(
    val choice: AlertChoice,
    val preview: String?,
    val contacts: List<ContactAlertPlan>,
    val lastTest: TestAlert?,
    val testAvailable: Boolean
)

data class EmergencyAlerts(val choice: AlertChoice, val alerts: List<ServerAlert>)

interface SafetyAlertRepository {
    suspend fun settings(): ApiResult<AlertSettings>
    suspend fun choose(on: Boolean): ApiResult<AlertSettings>
    suspend fun test(): ApiResult<AlertSettings>
    suspend fun forEmergency(emergencyId: Long): ApiResult<EmergencyAlerts>
}

/**
 * What the SOS screen says for one contact (§3.5 of PHASE_N6.md). Pure; the screen turns it into words.
 * Only a SENT / DELIVERED first text is ever shown as texted; SENT is always "delivery not confirmed".
 */
sealed interface ContactAlertLine {
    /** IRL is about to text them (waiting for the location or the 20 s). */
    data object Waiting : ContactAlertLine
    data class Texted(val at: Instant?, val late: Boolean) : ContactAlertLine
    data class Delivered(val at: Instant?) : ContactAlertLine
    /** IRL could not or will not text them: "Text <name> yourself". */
    data class TextYourself(val reason: SkipCause?) : ContactAlertLine
    /** Something this app does not understand: "not confirmed". */
    data object NotConfirmed : ContactAlertLine
}

object ServerAlertLines {

    /** The line for [contactId] from the server's answer (its FIRST text decides; follow-ups only add to it). */
    fun lineFor(contactId: Long, alerts: List<ServerAlert>): ContactAlertLine? {
        val first = alerts.firstOrNull { it.contactId == contactId && it.kind == ServerAlertKind.SOS_STARTED }
            ?: return null
        return when (first.status) {
            ServerAlertStatus.QUEUED -> ContactAlertLine.Waiting
            ServerAlertStatus.SENT -> ContactAlertLine.Texted(first.at, first.late)
            ServerAlertStatus.DELIVERED -> ContactAlertLine.Delivered(first.at)
            ServerAlertStatus.FAILED -> ContactAlertLine.TextYourself(null)
            ServerAlertStatus.SKIPPED -> ContactAlertLine.TextYourself(first.reason)
            ServerAlertStatus.UNKNOWN -> ContactAlertLine.NotConfirmed
        }
    }

    /** How long an SOS screen waits for an answer from IRL before it says "IRL can't text your Circle". */
    const val UNREACHABLE_AFTER_MILLIS = 15_000L

    /**
     * No answer from IRL for [UNREACHABLE_AFTER_MILLIS] since the SOS was shown ([since]) or since the last answer:
     * no internet, the laptop server off, the tunnel down: the phone cannot know which, and the advice is the same.
     */
    fun unreachable(since: Long, lastAnswer: Long?, now: Long, after: Long = UNREACHABLE_AFTER_MILLIS): Boolean =
        now - (lastAnswer ?: since) >= after

    /** True when IRL's closing text ("I'm safe" / false alarm) went out to that contact: no need to tell them by hand. */
    fun closingTexted(contactId: Long, alerts: List<ServerAlert>): Boolean = alerts.any {
        it.contactId == contactId && (it.kind == ServerAlertKind.SOS_RESOLVED || it.kind == ServerAlertKind.SOS_CANCELLED) &&
            (it.status == ServerAlertStatus.SENT || it.status == ServerAlertStatus.DELIVERED)
    }
}
