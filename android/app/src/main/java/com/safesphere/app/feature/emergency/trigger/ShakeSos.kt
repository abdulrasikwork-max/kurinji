package com.safesphere.app.feature.emergency.trigger

import com.safesphere.app.feature.emergency.domain.EmergencyTrigger
import com.safesphere.app.feature.emergency.domain.SosOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The notification side of a shake that happens while IRL is not on the screen. Production: [AndroidShakeCountdownNotifier]. */
interface ShakeCountdownNotifier {
    /** "SOS starts in [secondsLeft] s" with a Cancel button. */
    fun showCountdown(secondsLeft: Int)

    /** The SOS was started; tap to open it. */
    fun showStarted()

    fun clear()
}

/**
 * Phase 17F (D-4): the shake gesture as one application-scope holder. It decides WHEN the accelerometer is read
 * ([ShakeArming]), feeds the samples to the pure [ShakePolicy], and turns a detected shake into the cancellable
 * 5-second countdown - nothing starts without that countdown:
 *  - IRL on the screen: [foregroundShakes] fires and the SOS screen runs its own countdown (the same one as the button,
 *    tile and shortcut), so the SOS treatment, Cancel button and everything else stay exactly as they are.
 *  - IRL not on the screen (only while a Safe Walk runs): the countdown is a notification with a Cancel button; at zero
 *    the SOS is started on the phone through [startSos] (location sharing, Safety Circle) with no screen needed.
 *
 * The person can always cancel; a shake never starts anything for a person who did not switch the gesture on.
 */
class ShakeSos(
    private val scope: CoroutineScope,
    private val sensor: ShakeSensor,
    private val notifier: ShakeCountdownNotifier,
    userId: Flow<Long?>,
    options: (Long) -> Flow<SosOptions>,
    sosRunning: Flow<Boolean>,
    walkRunning: Flow<Boolean>,
    private val appVisible: StateFlow<Boolean>,
    private val notificationsAllowed: () -> Boolean,
    /** Starts the SOS on the phone without a screen; false when nobody is signed in. */
    private val startSos: suspend (EmergencyTrigger) -> Boolean,
    private val policy: ShakePolicy = ShakePolicy(),
    private val countdownSeconds: Int = 5,
    private val tickMillis: Long = 1_000L
) {

    private val _foregroundShakes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A shake while IRL is on the screen (collected by MainActivity and by the SOS screen). */
    val foregroundShakes: SharedFlow<Unit> = _foregroundShakes.asSharedFlow()

    private val _countdown = MutableStateFlow<Int?>(null)

    /** Seconds left of a notification countdown (null = none). */
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var state = ShakePolicy.State()
    private var countdownJob: Job? = null
    private var sosRunningNow = false

    init {
        val switchedOn = userId.flatMapLatest { id -> if (id == null) flowOf(false) else options(id).map { it.shakeOn } }
        scope.launch { sosRunning.collect { sosRunningNow = it } }
        scope.launch {
            combine(switchedOn, userId.map { it != null }, sosRunning, appVisible, walkRunning) { on, signedIn, sos, visible, walk ->
                ShakeArming.shouldListen(
                    ShakeArming.Inputs(on, signedIn, sos, visible, walk, notificationsAllowed())
                )
            }.distinctUntilChanged().collect { listen ->
                if (listen && sensor.isAvailable) {
                    state = ShakePolicy.State()
                    sensor.start(::onSample)
                    _listening.value = true
                } else {
                    sensor.stop()
                    _listening.value = false
                }
            }
        }
    }

    private fun onSample(atMillis: Long, x: Float, y: Float, z: Float) {
        val result = policy.onSample(state, atMillis, x, y, z)
        state = result.state
        if (result.triggered) onShake()
    }

    internal fun onShake() {
        if (sosRunningNow || countdownJob?.isActive == true) return
        if (appVisible.value) {
            _foregroundShakes.tryEmit(Unit)
        } else if (notificationsAllowed()) {
            startNotificationCountdown()
        }
        // Not visible and no notifications: there would be no way to cancel, so nothing happens
        // ([ShakeArming] does not listen in that case at all).
    }

    private fun startNotificationCountdown() {
        countdownJob = scope.launch {
            try {
                for (seconds in countdownSeconds downTo 1) {
                    _countdown.value = seconds
                    notifier.showCountdown(seconds)
                    delay(tickMillis)
                }
            } catch (cancelled: CancellationException) {
                _countdown.value = null
                notifier.clear()
                throw cancelled
            }
            _countdown.value = null
            if (sosRunningNow) {
                notifier.clear()
                return@launch
            }
            if (startSos(EmergencyTrigger.SHAKE)) notifier.showStarted() else notifier.clear()
        }
    }

    /** Cancel on the countdown notification (or any later way to say "no"). Nothing was started. */
    fun cancelCountdown() {
        countdownJob?.cancel()
    }
}
