package com.safesphere.app.feature.safety

import com.safesphere.app.R
import com.safesphere.app.core.sms.SmsAvailability
import com.safesphere.app.feature.safety.ui.ReadyAction
import com.safesphere.app.feature.safety.ui.ReadyCheck
import com.safesphere.app.feature.safety.ui.ReadyInputs
import com.safesphere.app.feature.safety.ui.ReadyMark
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 13F (F12, D10): the Ready check states facts about this phone, each with the screen that changes it, and never
 * says the person is ready, safe or protected.
 */
class ReadyCheckTest {

    private val all = ReadyInputs(circleKnown = true, circlePeople = 2, autoSms = true, sms = SmsAvailability.AVAILABLE,
        locationAllowed = true, notificationsAllowed = true)

    @Test
    fun `everything set shows facts, and the only rows with a button open the screen that changes them`() {
        val rows = ReadyCheck.rows(all)
        assertEquals(listOf(R.plurals.ready_circle_people, R.string.ready_sms_on, R.string.ready_location_on,
            R.string.ready_notifications_on, R.string.ready_shortcut), rows.map { it.fact })
        assertEquals(2, rows.first().count)
        assertTrue(rows.none { it.mark == ReadyMark.NEEDS_ATTENTION })
    }

    @Test
    fun `missing pieces are marked and lead to their screen`() {
        val rows = ReadyCheck.rows(all.copy(circlePeople = 0, sms = SmsAvailability.PERMISSION_NEEDED,
            locationAllowed = false, notificationsAllowed = false))
        val byFact = rows.associateBy { it.fact }
        assertEquals(ReadyMark.NEEDS_ATTENTION, byFact.getValue(R.string.ready_circle_empty).mark)
        assertEquals(ReadyAction.OPEN_SAFETY_CIRCLE, byFact.getValue(R.string.ready_sms_permission).action)
        assertEquals(ReadyAction.OPEN_APP_SETTINGS, byFact.getValue(R.string.ready_location_off).action)
        assertEquals(ReadyAction.OPEN_APP_SETTINGS, byFact.getValue(R.string.ready_notifications_off).action)
    }

    @Test
    fun `automatic SMS off is a choice, not a problem, and a phone without SMS gets no button`() {
        assertEquals(ReadyMark.CHOICE, ReadyCheck.rows(all.copy(autoSms = false)).first { it.fact == R.string.ready_sms_off }.mark)
        val noSms = ReadyCheck.rows(all.copy(sms = SmsAvailability.NOT_SUPPORTED)).first { it.fact == R.string.ready_sms_not_supported }
        assertEquals(null, noSms.action)
        assertTrue("an unread Circle copy is not reported as empty",
            ReadyCheck.rows(all.copy(circleKnown = false, circlePeople = 0)).any { it.fact == R.string.ready_circle_unknown })
        assertFalse("no notification row where the phone does not ask",
            ReadyCheck.rows(all.copy(notificationsAllowed = null)).any { it.fact == R.string.ready_notifications_on })
    }

    @Test
    fun `no Ready check text gives a verdict`() {
        val xml = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)
        val texts = Regex("<(string|plurals) name=\"(ready_[a-z_]+)\"[^>]*>(.*?)</(string|plurals)>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[2] to it.groupValues[3].lowercase() }
        assertTrue(texts.size >= 14)
        for ((name, text) in texts) {
            val rest = text.replace("safe walk", "").replace("safety circle", "")
            for (word in listOf("you are ready", "you're ready", "all set", "you\\'re all", "protected", "covered",
                    "nothing to worry", "guarantee")) assertFalse("$name: $word", word in rest)
            assertFalse("$name claims safety: $text", Regex("\\bsafe\\b").containsMatchIn(rest))
        }
    }
}
