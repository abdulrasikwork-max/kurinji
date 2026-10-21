package com.safesphere.app.core.device

import com.safesphere.app.core.device.BatteryLevel.CRITICAL
import com.safesphere.app.core.device.BatteryLevel.LOW
import com.safesphere.app.core.device.BatteryLevel.NORMAL
import com.safesphere.app.core.device.BatteryLevel.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 4C battery policy (pure). Required case: "low battery handled". */
class BatteryPolicyTest {

    private val policy = BatteryPolicy()

    @Test
    fun `low battery handled - thresholds at 20 and 10 percent`() {
        assertEquals(NORMAL, policy.evaluate(UNKNOWN, 21, false))
        assertEquals(LOW, policy.evaluate(NORMAL, 20, false))
        assertEquals(CRITICAL, policy.evaluate(LOW, 10, false))
        assertEquals(CRITICAL, policy.evaluate(NORMAL, 3, false)) // a sudden drop goes straight to critical
    }

    @Test
    fun `hysteresis - a level is left only five points above its threshold`() {
        assertEquals(LOW, policy.evaluate(LOW, 22, false))
        assertEquals(NORMAL, policy.evaluate(LOW, 25, false))
        assertEquals(CRITICAL, policy.evaluate(CRITICAL, 13, false))
        assertEquals(LOW, policy.evaluate(CRITICAL, 15, false))
        assertEquals(NORMAL, policy.evaluate(CRITICAL, 40, false))
    }

    @Test
    fun `charging is normal, and an unknown level is not treated as low`() {
        assertEquals(NORMAL, policy.evaluate(CRITICAL, 5, true))
        assertEquals(UNKNOWN, policy.evaluate(NORMAL, null, false))
        assertEquals(UNKNOWN, policy.evaluate(LOW, null, null))
    }

    @Test
    fun `location is requested half as often when the battery is low or critical`() {
        assertEquals(30_000L, policy.locationIntervalMillis(NORMAL, 30_000))
        assertEquals(30_000L, policy.locationIntervalMillis(UNKNOWN, 30_000))
        assertEquals(60_000L, policy.locationIntervalMillis(LOW, 30_000))
        assertEquals(60_000L, policy.locationIntervalMillis(CRITICAL, 30_000))
    }
}
