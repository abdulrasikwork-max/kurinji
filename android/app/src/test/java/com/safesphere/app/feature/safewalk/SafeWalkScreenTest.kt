package com.safesphere.app.feature.safewalk

import com.safesphere.app.core.escalation.EscalationLevel
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safewalk.domain.Journey
import com.safesphere.app.feature.safewalk.domain.JourneyStatus
import com.safesphere.app.feature.safewalk.session.SafeWalkState
import com.safesphere.app.feature.safewalk.ui.SafeWalkUiState
import com.safesphere.app.feature.safewalk.ui.SafeWalkViewModel
import com.safesphere.app.testing.FakeSafeWalkController
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** N6 (D22, D25): the "On my way" screen's view model, its words, and the guards of what it may never do. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeWalkScreenTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val controller = FakeSafeWalkController()
    private fun viewModel() = SafeWalkViewModel(controller)

    private val journey = Journey(
        1, JourneyStatus.ACTIVE, "Library", 20, EscalationLevel.NORMAL,
        Instant.parse("2026-10-20T18:00:00Z"), null, Instant.parse("2026-10-20T18:20:00Z"), Instant.parse("2026-10-20T18:00:00Z")
    )

    @Test
    fun `the screen recovers first, then offers the setup`() = runTest {
        controller.recoverGate = CompletableDeferred()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(SafeWalkUiState.Loading, vm.uiState.value)
        controller.recoverGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SafeWalkUiState.Setup)
    }

    @Test
    fun `starting sends only the label and one of the design's times, and twice quickly starts one walk`() = runTest {
        controller.startResult = ApiResult.Success(journey)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onStartClicked("Library", 7) // not a choice on the screen: refused here
        vm.onStartClicked("Library", 20)
        vm.onStartClicked("Library", 20)
        advanceUntilIdle()
        assertEquals(listOf("recover", "start:20:Library"), controller.calls)
    }

    @Test
    fun `a refused start shows the reason, an active walk and an ended one are shown`() = runTest {
        controller.startResult = ApiResult.Failure(ApiError.CONFLICT, "A Safe Walk is already active")
        val vm = viewModel()
        advanceUntilIdle()
        vm.onStartClicked(null, 10)
        advanceUntilIdle()
        assertEquals(ApiError.CONFLICT, (vm.uiState.value as SafeWalkUiState.Setup).failure?.error)

        controller.state.value = SafeWalkState(journey = journey, localDueAt = journey.nextCheckInDueAt)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is SafeWalkUiState.Active)
        assertEquals("Library", vm.lastLabel)
        controller.state.value = controller.state.value.copy(endedStatus = JourneyStatus.COMPLETED)
        advanceUntilIdle()
        assertEquals(SafeWalkUiState.Ended(JourneyStatus.COMPLETED), vm.uiState.value)
        vm.onDoneClicked()
        assertTrue("acknowledge" in controller.calls)
    }

    @Test
    fun `More time, I'm there and stop reach the holder`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onMoreTimeClicked()
        vm.onThereClicked()
        vm.onCancelConfirmed()
        assertEquals(listOf("recover", "checkIn", "complete", "cancel"), controller.calls)
    }

    private val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
    private fun string(name: String) =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(strings)?.groupValues?.get(1)
            ?: error("string $name not found")

    @Test
    fun `the words are honest - a reminder that may be late, no tracking, no safe claim, the name On my way`() {
        assertEquals("On my way", string("title_safe_walk"))
        assertEquals("On my way", string("feature_safe_walk_title"))
        assertTrue(string("omw_setup_honest").contains("may come a few minutes late"))
        assertTrue(string("omw_setup_honest").contains("doesn\\'t use your location"))
        val all = Regex("<string name=\"(omw_[a-z_]+)\">(.*?)</string>").findAll(strings).map { it.groupValues[2].lowercase() }.toList()
        assertTrue(all.size >= 30)
        for (text in all) {
            assertFalse(text, Regex("\\b(is|are|you're|you\\\\'re) safe\\b|\\bsafe walk\\b|protected|guarantee").containsMatchIn(text))
        }
    }

    @Test
    fun `each reminder has at most three buttons, the nudge keeps SOS and Call 112 (N6-C, found on the emulator)`() {
        val alerts = File("src/main/java/com/safesphere/app/feature/safewalk/session/AndroidSafeWalkAlerts.kt").readText()
        val show = alerts.substringAfter("override fun show(").substringBefore("override fun clear(")
        val shared = show.substringBefore("if (nudge) {")
        val nudge = show.substringAfter("if (nudge) {").substringBefore("} else {")
        val reminder = show.substringAfter("} else {").substringBefore("notify(")
        fun actions(part: String) = Regex("""\.addAction\(""").findAll(part).count()
        assertTrue("Android shows at most three buttons", actions(shared) + actions(nudge) <= 3)
        assertTrue(actions(shared) + actions(reminder) <= 3)
        assertTrue(shared.contains("omw_action_there"))
        assertTrue(nudge.contains("omw_action_sos") && nudge.contains("omw_action_call"))
        assertTrue(reminder.contains("omw_action_more_time"))
        assertFalse("the nudge text names no button it does not have", string("omw_nudge_text").contains("More time"))
    }

    @Test
    fun `no visible text calls the feature Safe Walk, and no text says On my way uses location (N6-C)`() {
        // Only the two lines that describe records of walks started before N6 keep the old name.
        val history = setOf("detail_walk_destination", "alert_kind_walk_no_response")
        val named = Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(strings)
            .filter { it.groupValues[1] !in history && it.groupValues[2].contains("Safe Walk", ignoreCase = true) }
            .map { it.groupValues[1] }.toList()
        assertEquals(emptyList<String>(), named)
        assertTrue(string("ready_location_on").contains("an SOS can share where you are"))
        assertFalse(string("ready_location_off").contains("On my way"))
        val channels = File("src/main/java/com/safesphere/app/core/notifications/NotificationChannels.kt").readText()
        assertTrue("the shake countdown channel is deleted once", channels.contains("deleteNotificationChannel(RETIRED_SOS_COUNTDOWN)"))
        assertFalse(channels.contains("createNotificationChannel(countdown)"))
    }

    @Test
    fun `the notes hold only the label and the time - no location, no link, nobody else's name`() {
        for (name in listOf("omw_note_going_to", "omw_note_going", "omw_note_there_to", "omw_note_there")) {
            val text = string(name)
            assertFalse(name, text.contains("http") || text.contains("maps") || text.contains("location", ignoreCase = true))
        }
        val fragment = File("src/main/java/com/safesphere/app/feature/safewalk/ui/SafeWalkFragment.kt").readText()
        assertTrue(fragment.contains("Intent.ACTION_SEND"))
        assertFalse(fragment.contains("LocationAsk") || fragment.contains("TrackingServiceStarter"))
    }

    @Test
    fun `the reminder needs no permission - no exact alarm, no boot receiver, receivers not exported`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("SCHEDULE_EXACT_ALARM") || manifest.contains("USE_EXACT_ALARM"))
        assertFalse(manifest.contains("RECEIVE_BOOT_COMPLETED") || manifest.contains("BOOT_COMPLETED"))
        assertTrue(Regex("""OnMyWayAlarmReceiver"\s+android:exported="false"""").containsMatchIn(manifest))
        val scheduler = File("src/main/java/com/safesphere/app/feature/safewalk/session/AndroidReminderScheduler.kt").readText()
        assertTrue(scheduler.contains("setAndAllowWhileIdle"))
        assertFalse(scheduler.contains("setExact"))
    }
}
