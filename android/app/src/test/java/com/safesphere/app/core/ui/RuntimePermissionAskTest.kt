package com.safesphere.app.core.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17H (real-device sprint): the shared rule for runtime permissions other than location. These two checks lived
 * in Phase17HPermissionDenialTest beside the Nearby screen's own cases; Nearby was retired in N1, the rule stays.
 */
class RuntimePermissionAskTest {

    @Test
    fun `settings are offered only after this screen asked and Android will not ask again`() {
        assertFalse("never asked: ask", RuntimePermissionAsk.needsSettings(asked = false, shouldShowRationale = false))
        assertFalse("never asked, rationale shown: ask", RuntimePermissionAsk.needsSettings(asked = false, shouldShowRationale = true))
        assertFalse("asked once, Android would ask again: ask", RuntimePermissionAsk.needsSettings(asked = true, shouldShowRationale = true))
        assertTrue("asked, no rationale: the dialog will not come back", RuntimePermissionAsk.needsSettings(asked = true, shouldShowRationale = false))
    }

    @Test
    fun `the SOS options buttons take label AND action from the same rule`() {
        val fragment = File("src/main/java/com/safesphere/app/feature/safety/ui/SosOptionsFragment.kt").readText(Charsets.UTF_8)
        assertTrue(fragment.contains("RuntimePermissionAsk.needsSettings"))
        assertFalse("no second, hand-written copy of the rule",
            Regex("asked\\w+ && !shouldShowRequestPermissionRationale").containsMatchIn(fragment))
        assertTrue("the notification button is relabelled", fragment.contains("shakeNotificationsButton.setText("))
    }
}
