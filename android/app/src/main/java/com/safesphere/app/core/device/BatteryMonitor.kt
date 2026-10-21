package com.safesphere.app.core.device

import android.content.Context
import android.os.BatteryManager

/** Reads the battery. Only a reader: what to do about it is [BatteryPolicy] (Phase 4C). */
interface BatteryMonitor {
    /** 0..100, or null when the device cannot tell. */
    fun batteryPercent(): Int?

    /** True while plugged in and charging; null when the device cannot tell. */
    fun isCharging(): Boolean? = null
}

/** Uses BatteryManager; needs no permission. */
class AndroidBatteryMonitor(context: Context) : BatteryMonitor {

    private val batteryManager: BatteryManager? =
        context.applicationContext.getSystemService(BatteryManager::class.java)

    override fun batteryPercent(): Int? {
        val value = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        // Integer.MIN_VALUE (or 0 on some emulators/devices without a battery) means "unknown".
        return value.takeIf { it in 1..100 }
    }

    override fun isCharging(): Boolean? = batteryManager?.isCharging
}
