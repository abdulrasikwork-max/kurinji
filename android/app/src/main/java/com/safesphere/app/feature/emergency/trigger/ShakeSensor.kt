package com.safesphere.app.feature.emergency.trigger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/** Where accelerometer samples come from. Production: [AndroidShakeSensor]. Tests: a fake. */
interface ShakeSensor {
    /** False on a phone without an accelerometer: the shake switch is then not offered. */
    val isAvailable: Boolean

    /** Starts delivering samples ([atMillis] = a monotonic clock). Calling it again replaces the listener. */
    fun start(onSample: (atMillis: Long, x: Float, y: Float, z: Float) -> Unit)

    fun stop()
}

/**
 * Phase 17F: the platform accelerometer. Uses the WAKE-UP variant when the phone has one, because the ordinary one may
 * deliver nothing (or old, batched samples) while the screen is off and the CPU sleeps; that costs some battery, which
 * is why it is only registered while IRL is on the screen or a Safe Walk runs ([ShakeArming]).
 * Rate: SENSOR_DELAY_GAME (about 50 Hz) - enough to see a shake, no more.
 */
class AndroidShakeSensor(context: Context) : ShakeSensor {

    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var listener: SensorEventListener? = null

    override val isAvailable: Boolean get() = sensor != null

    override fun start(onSample: (Long, Float, Float, Float) -> Unit) {
        stop()
        val manager = manager ?: return
        val sensor = sensor ?: return
        val next = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                // event.timestamp is nanoseconds on the elapsed-realtime clock; the policy needs milliseconds.
                val sampleMillis = event.timestamp / 1_000_000L
                // A late batch (a sensor that sleeps with the screen off) is history, not a shake happening now.
                if (!ShakePolicy.isFresh(sampleMillis, SystemClock.elapsedRealtime())) return
                onSample(sampleMillis, event.values[0], event.values[1], event.values[2])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = next
        // Reports are batched for [MAX_REPORT_LATENCY_US]: a wake-up sensor otherwise wakes the CPU for every sample (about 50 a
        // second), which would drain the battery during a long Safe Walk. Timestamps stay the sensor's own, so the policy
        // still sees the true spacing of the shakes; the only cost is that a shake is noticed up to a third of a second later.
        manager.registerListener(next, sensor, SensorManager.SENSOR_DELAY_GAME, MAX_REPORT_LATENCY_US)
    }

    override fun stop() {
        val current = listener ?: return
        listener = null
        manager?.unregisterListener(current)
    }

    private companion object {
        const val MAX_REPORT_LATENCY_US = 300_000
    }

}
