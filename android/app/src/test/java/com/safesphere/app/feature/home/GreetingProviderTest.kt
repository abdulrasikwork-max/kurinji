package com.safesphere.app.feature.home

import com.safesphere.app.feature.home.ui.GreetingPeriod
import com.safesphere.app.feature.home.ui.GreetingProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingProviderTest {

    @Test
    fun `morning runs from 5 to 11`() {
        assertEquals(GreetingPeriod.MORNING, GreetingProvider.periodFor(5))
        assertEquals(GreetingPeriod.MORNING, GreetingProvider.periodFor(11))
    }

    @Test
    fun `afternoon runs from 12 to 16`() {
        assertEquals(GreetingPeriod.AFTERNOON, GreetingProvider.periodFor(12))
        assertEquals(GreetingPeriod.AFTERNOON, GreetingProvider.periodFor(16))
    }

    @Test
    fun `evening covers the rest of the day including after midnight`() {
        assertEquals(GreetingPeriod.EVENING, GreetingProvider.periodFor(17))
        assertEquals(GreetingPeriod.EVENING, GreetingProvider.periodFor(23))
        assertEquals(GreetingPeriod.EVENING, GreetingProvider.periodFor(0))
        assertEquals(GreetingPeriod.EVENING, GreetingProvider.periodFor(4))
    }
}
