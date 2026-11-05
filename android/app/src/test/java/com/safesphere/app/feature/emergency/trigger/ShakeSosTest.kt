package com.safesphere.app.feature.emergency.trigger

import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import com.safesphere.app.feature.emergency.domain.SosOptions
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 17F (D-4): the holder that turns a detected shake into the cancellable countdown. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShakeSosTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeSensor : ShakeSensor {
        override var isAvailable = true
        var listener: ((Long, Float, Float, Float) -> Unit)? = null
        val running get() = listener != null
        override fun start(onSample: (Long, Float, Float, Float) -> Unit) { listener = onSample }
        override fun stop() { listener = null }

        /** Three firm back-and-forth strokes starting at [at]. */
        fun shake(at: Long = 1_000) {
            val l = listener ?: error("not listening")
            l(at - 20, 0f, 0f, 9.81f)
            l(at, 45f, 0f, 9.81f)
            l(at + 100, -45f, 0f, 9.81f)
            l(at + 200, 45f, 0f, 9.81f)
        }
    }

    private class FakeNotifier : ShakeCountdownNotifier {
        val counts = mutableListOf<Int>()
        var started = 0
        var cleared = 0
        override fun showCountdown(secondsLeft: Int) { counts += secondsLeft }
        override fun showStarted() { started++ }
        override fun clear() { cleared++ }
    }

    private val sensor = FakeSensor()
    private val notifier = FakeNotifier()
    private val user = MutableStateFlow<Long?>(7L)
    private val switchedOn = MutableStateFlow(true)
    private val sosRunning = MutableStateFlow(false)
    private val walk = MutableStateFlow(false)
    private val visible = MutableStateFlow(true)
    private var notificationsAllowed = true
    private val started = mutableListOf<EmergencyTrigger>()
    private var signedInForStart = true

    private fun TestScope.holder(): ShakeSos = ShakeSos(
        scope = backgroundScope,
        sensor = sensor,
        notifier = notifier,
        userId = user,
        options = { id -> combine(switchedOn, flowOf(id)) { on, _ -> SosOptions(shakeOn = on) } },
        sosRunning = sosRunning,
        walkRunning = walk,
        appVisible = visible,
        notificationsAllowed = { notificationsAllowed },
        startSos = { trigger -> started += trigger; signedInForStart }
    ).also { runCurrent() }

    @Test
    fun `it listens only while armed`() = runTest {
        holder()
        assertTrue(sensor.running)
        switchedOn.value = false
        runCurrent()
        assertFalse(sensor.running)
        switchedOn.value = true
        runCurrent()
        assertTrue(sensor.running)
        visible.value = false // off the screen, no walk
        runCurrent()
        assertFalse(sensor.running)
        walk.value = true
        runCurrent()
        assertTrue(sensor.running)
        sosRunning.value = true // an SOS is running: stop listening
        runCurrent()
        assertFalse(sensor.running)
    }

    @Test
    fun `a phone without a motion sensor never listens`() = runTest {
        sensor.isAvailable = false
        holder()
        assertFalse(sensor.running)
    }

    @Test
    fun `a shake while IRL is on the screen opens the screen countdown and starts nothing`() = runTest {
        val shakes = holder()
        var events = 0
        backgroundScope.launch { shakes.foregroundShakes.collect { events++ } }
        runCurrent()
        sensor.shake()
        runCurrent()
        assertEquals(1, events)
        assertTrue(started.isEmpty())
        assertTrue(notifier.counts.isEmpty())
    }

    @Test
    fun `a shake during a Safe Walk with the screen off counts down in a notification and then starts the SOS`() = runTest {
        visible.value = false
        walk.value = true
        val shakes = holder()
        sensor.shake()
        runCurrent()
        assertEquals(5, shakes.countdown.value)
        advanceTimeBy(4_500)
        assertEquals(listOf(5, 4, 3, 2, 1), notifier.counts)
        assertTrue("nothing starts before the countdown ends", started.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(EmergencyTrigger.SHAKE), started)
        assertEquals(1, notifier.started)
        assertNull(shakes.countdown.value)
    }

    @Test
    fun `cancel during the countdown starts nothing and clears the notification`() = runTest {
        visible.value = false
        walk.value = true
        val shakes = holder()
        sensor.shake()
        runCurrent()
        advanceTimeBy(2_500)
        shakes.cancelCountdown()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(started.isEmpty())
        assertEquals(0, notifier.started)
        assertTrue(notifier.cleared >= 1)
        assertNull(shakes.countdown.value)
    }

    @Test
    fun `an SOS the person starts meanwhile is not started a second time`() = runTest {
        visible.value = false
        walk.value = true
        holder()
        sensor.shake()
        runCurrent()
        advanceTimeBy(2_000)
        sosRunning.value = true
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(started.isEmpty())
        assertEquals(0, notifier.started)
    }

    @Test
    fun `when nobody is signed in at the end of the countdown nothing is announced as started`() = runTest {
        visible.value = false
        walk.value = true
        signedInForStart = false
        holder()
        sensor.shake()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, started.size)
        assertEquals(0, notifier.started)
    }

    @Test
    fun `without notifications a shake off the screen does nothing`() = runTest {
        visible.value = false
        walk.value = true
        notificationsAllowed = false
        val shakes = holder()
        // The holder does not listen at all in this state ...
        assertFalse(sensor.running)
        // ... and even a shake that somehow arrives must not start a countdown nobody could cancel.
        shakes.onShake()
        runCurrent()
        assertTrue(notifier.counts.isEmpty())
        assertTrue(started.isEmpty())
    }
}
