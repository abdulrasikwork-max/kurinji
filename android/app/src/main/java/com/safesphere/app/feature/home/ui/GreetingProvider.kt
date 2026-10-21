package com.safesphere.app.feature.home.ui

enum class GreetingPeriod { MORNING, AFTERNOON, EVENING }

/** Chooses "Good morning / afternoon / evening" from the hour on the device clock. No location is used. */
object GreetingProvider {
    fun periodFor(hourOfDay: Int): GreetingPeriod = when (hourOfDay) {
        in 5..11 -> GreetingPeriod.MORNING
        in 12..16 -> GreetingPeriod.AFTERNOON
        else -> GreetingPeriod.EVENING
    }
}
