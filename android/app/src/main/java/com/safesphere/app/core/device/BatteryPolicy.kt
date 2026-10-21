package com.safesphere.app.core.device

/** How worried SafeSphere should be about the battery during a Safe Walk. */
enum class BatteryLevel { UNKNOWN, NORMAL, LOW, CRITICAL }

data class BatteryStatus(
    val percent: Int? = null,
    val charging: Boolean = false,
    val level: BatteryLevel = BatteryLevel.UNKNOWN
)

/**
 * Battery policy (Phase 4C). Pure Kotlin, unit-tested.
 *
 * Why it matters: if the phone dies, nothing on it can check on the person any more (there is no server-side
 * alert yet). So the policy (1) warns early, (2) saves power by asking for location half as often when LOW or
 * CRITICAL, and (3) the session sends the latest location once when CRITICAL, so the last known place on the
 * server is as fresh as possible. It never changes the check-in deadline: a shorter interval would not help
 * after the phone dies, and the person agreed to the one they chose.
 *
 * Hysteresis: a level is left only [recoveryMargin] points above its threshold, so 19 % / 21 % does not flap.
 * Charging means NORMAL (power is coming in). An emergency (SOS) does not use this policy: there, location
 * matters more than battery.
 */
class BatteryPolicy(
    private val lowPercent: Int = 20,
    private val criticalPercent: Int = 10,
    private val recoveryMargin: Int = 5
) {

    fun evaluate(previous: BatteryLevel, percent: Int?, charging: Boolean?): BatteryLevel {
        if (charging == true) return BatteryLevel.NORMAL
        if (percent == null) return BatteryLevel.UNKNOWN
        return when {
            percent <= criticalPercent -> BatteryLevel.CRITICAL
            previous == BatteryLevel.CRITICAL && percent < criticalPercent + recoveryMargin -> BatteryLevel.CRITICAL
            percent <= lowPercent -> BatteryLevel.LOW
            (previous == BatteryLevel.LOW || previous == BatteryLevel.CRITICAL) && percent < lowPercent + recoveryMargin ->
                BatteryLevel.LOW
            else -> BatteryLevel.NORMAL
        }
    }

    /** Location request interval for a Safe Walk at this battery level. */
    fun locationIntervalMillis(level: BatteryLevel, normalMillis: Long): Long = when (level) {
        BatteryLevel.LOW, BatteryLevel.CRITICAL -> normalMillis * 2
        BatteryLevel.NORMAL, BatteryLevel.UNKNOWN -> normalMillis
    }
}
