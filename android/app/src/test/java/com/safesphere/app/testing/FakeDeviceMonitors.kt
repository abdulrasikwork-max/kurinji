package com.safesphere.app.testing

import com.safesphere.app.core.device.BatteryMonitor
import com.safesphere.app.core.device.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow

class FakeBatteryMonitor(var percent: Int? = 80, var charging: Boolean? = false) : BatteryMonitor {
    override fun batteryPercent(): Int? = percent
    override fun isCharging(): Boolean? = charging
}

class FakeConnectivityMonitor(online: Boolean = true) : ConnectivityMonitor {
    override val isOnline = MutableStateFlow(online)
}
