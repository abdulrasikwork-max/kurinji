package com.safesphere.app.core.push

import java.io.File
import java.time.LocalTime
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** N7 (D26 to D34): the phone's pure push rules. */
class PushCoreTest {

    private val event = "0b6f6a39-2f8e-4c5e-9a43-111122223333"
    private fun data(t: String = "REPLY_TURN", s: String = "abc-123", m: String = event, v: String = "1") =
        mapOf("t" to t, "s" to s, "m" to m, "v" to v)

    @Test
    fun `a pointer is exactly kind, opaque subject, event id and version 1`() {
        assertEquals(PushPayload(PushKind.REPLY_TURN, "abc-123", event), PushPayload.parse(data()))
        assertNull(PushPayload.parse(data(v = "2")))
        assertNull(PushPayload.parse(data(t = "SAME")))
        assertNull(PushPayload.parse(data(t = "reply_turn")))
        assertNull(PushPayload.parse(data(s = "a/b")))
        assertNull(PushPayload.parse(data(s = "")))
        assertNull(PushPayload.parse(data(s = "x".repeat(65))))
        assertNull(PushPayload.parse(data(m = "NOT-AN-EVENT")))
        assertNull(PushPayload.parse(mapOf("t" to "REPLY_TURN", "s" to "abc", "v" to "1")))
    }

    @Test
    fun `the phone knows exactly the server's fourteen kinds`() {
        val server = File("../../backend/src/main/java/com/safesphere/backend/push/entity/PushType.java").readText()
        val names = Regex("""^\s{4}([A-Z_]+)\(""", RegexOption.MULTILINE).findAll(server).map { it.groupValues[1] }.toList()
        assertEquals(14, names.size)
        assertEquals(names, PushKind.entries.map { it.name })
    }

    @Test
    fun `only the safety kinds are not social, and the SOS and On my way use their own channels`() {
        assertEquals(setOf(PushKind.ACCOUNT_SIGN_IN, PushKind.MEMBERSHIP_DECIDED, PushKind.CONTENT_DECIDED,
            PushKind.CIRCLE_REQUEST, PushKind.CIRCLE_SOS, PushKind.OMW_REMINDER, PushKind.STAFF_WAITING),
            PushKind.entries.filterNot { it.social }.toSet())
        assertEquals("irl_circle_sos", PushKind.CIRCLE_SOS.channel.id)
        assertEquals("safety_alerts", PushKind.OMW_REMINDER.channel.id) // the existing On my way channel
    }

    @Test
    fun `a tap target opens only known screens with well-formed ids`() {
        val uuid = "0b6f6a39-2f8e-4c5e-9a43-111122223333"
        assertTrue(PushTarget.from("moment", uuid) is PushTarget.Moment)
        assertTrue(PushTarget.from("conversation", uuid) is PushTarget.Conversation)
        assertTrue(PushTarget.from("circle_alert", "121") is PushTarget.CircleAlert)
        assertEquals(PushTarget.Replies, PushTarget.from("replies", "ignored"))
        assertNull(PushTarget.from("moment", "../../etc"))
        assertNull(PushTarget.from("moment", null))
        assertNull(PushTarget.from("circle_alert", "0"))
        assertNull(PushTarget.from("circle_alert", "-1"))
        assertNull(PushTarget.from("settings", null))
        assertNull(PushTarget.from(null, uuid))
        for (t in listOf(PushTarget.Moment(uuid), PushTarget.Requests, PushTarget.CircleAlert("7"), PushTarget.OnMyWay)) {
            assertEquals(t.name, PushTarget.from(t.name, t.id)?.name)
        }
    }

    @Test
    fun `quiet at night silences social kinds from 11 pm to 7 am only, never safety`() {
        assertTrue(PushRules.silentNow(PushKind.REPLY_TURN, true, LocalTime.of(23, 0)))
        assertTrue(PushRules.silentNow(PushKind.PLAN_ASK, true, LocalTime.of(2, 30)))
        assertTrue(PushRules.silentNow(PushKind.PLAN_ASK, true, LocalTime.of(6, 59)))
        assertFalse(PushRules.silentNow(PushKind.PLAN_ASK, true, LocalTime.of(7, 0)))
        assertFalse(PushRules.silentNow(PushKind.PLAN_ASK, true, LocalTime.of(22, 59)))
        assertFalse(PushRules.silentNow(PushKind.PLAN_ASK, false, LocalTime.of(2, 0)))
        assertFalse(PushRules.silentNow(PushKind.CIRCLE_SOS, true, LocalTime.of(2, 0)))
        assertFalse(PushRules.silentNow(PushKind.OMW_REMINDER, true, LocalTime.of(2, 0)))
    }

    @Test
    fun `while IRL is open only safety and account kinds reach the tray`() {
        assertFalse(PushRules.showInTray(PushKind.REPLY_TURN, appVisible = true))
        assertTrue(PushRules.showInTray(PushKind.REPLY_TURN, appVisible = false))
        assertTrue(PushRules.showInTray(PushKind.CIRCLE_SOS, appVisible = true))
        assertTrue(PushRules.showInTray(PushKind.OMW_REMINDER, appVisible = true))
    }

    @Test
    fun `one stable, non-zero notification id per thread`() {
        assertEquals(PushRules.notificationId("plan-x"), PushRules.notificationId("plan-x"))
        assertNotEquals(PushRules.notificationId("plan-x"), PushRules.notificationId("plan-y"))
        assertTrue(listOf("", "a", "plan-x", "conversation-1").all { PushRules.notificationId(it) > 0 })
    }

    @Test
    fun `the token is registered per sign-in, forgotten at sign-out, and never without permission or set-up`() {
        val r = PushRules::registration
        assertEquals(PushRules.Registration.REGISTER, r(true, "s1", true, "t1", null))
        assertEquals(PushRules.Registration.NOTHING, r(true, "s1", true, "t1", "s1" to "t1"))
        assertEquals(PushRules.Registration.REGISTER, r(true, "s2", true, "t1", "s1" to "t1"))
        assertEquals(PushRules.Registration.REGISTER, r(true, "s1", true, "t2", "s1" to "t1"))
        assertEquals(PushRules.Registration.NOTHING, r(true, "s1", false, "t1", null))
        assertEquals(PushRules.Registration.NOTHING, r(true, "s1", true, null, null))
        assertEquals(PushRules.Registration.FORGET, r(true, null, true, "t1", "s1" to "t1"))
        assertEquals(PushRules.Registration.NOTHING, r(true, null, true, null, null))
        assertEquals(PushRules.Registration.NOTHING, r(false, "s1", true, "t1", null))
    }

    @Test
    fun `the session id is read from the sign-in token`() {
        fun jwt(payload: String) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"
        assertEquals("AbC_12-x", PushRules.sessionIdOf(jwt("""{"sub":"3","sid":"AbC_12-x","exp":1}""")))
        assertNull(PushRules.sessionIdOf(jwt("""{"sub":"3"}""")))
        assertNull(PushRules.sessionIdOf("not a token"))
        assertNull(PushRules.sessionIdOf(null))
        assertNull(PushRules.sessionIdOf("h.%%%.s"))
    }

    @Test
    fun `the card asks only on Android 13+, when set up, not granted, and not within 30 days of Not now`() {
        assertTrue(NotificationAsk.shouldAsk(true, true, false, 100, 0))
        assertFalse(NotificationAsk.shouldAsk(false, true, false, 100, 0))
        assertFalse(NotificationAsk.shouldAsk(true, false, false, 100, 0))
        assertFalse(NotificationAsk.shouldAsk(true, true, true, 100, 0))
        assertFalse(NotificationAsk.shouldAsk(true, true, false, 100, 101))
        assertEquals(30L, PushRules.NOT_NOW_DAYS)
    }

    @Test
    fun `an open request is used once and expires`() {
        val request = PushOpenRequest()
        assertNull(request.consume(0))
        request.request(PushTarget.Replies, 1_000)
        assertEquals(PushTarget.Replies, request.consume(2_000))
        assertNull(request.consume(2_000))
        request.request(PushTarget.Replies, 1_000)
        assertNull(request.consume(1_000 + PushOpenRequest.VALID_MS + 1))
    }
}
