package com.safesphere.app.core.push

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N7 (D26, D30, D34): one pointer in, at most one notification out; registration follows the sign-in (D28). */
class PushRouterTest {

    private class Tray : PushTray {
        val shown = ArrayList<String>()
        var cancelledAll = 0
        override fun show(kind: PushKind, notice: PushNotice, silent: Boolean) {
            shown += "${kind.name}:${notice.title}:${if (silent) "silent" else "sound"}"
        }
        override fun showGeneric(kind: PushKind, silent: Boolean) {
            shown += "${kind.name}:generic"
        }
        override fun cancel(thread: String) = Unit
        override fun cancelAll() {
            cancelledAll++
        }
    }

    private fun data(kind: PushKind, event: String = "00000000-0000-0000-0000-000000000001") =
        mapOf("t" to kind.name, "s" to "abc", "m" to event, "v" to "1")

    private fun notice(title: String) = Rendered.Show(PushNotice(title, null, "t", PushTarget.Replies, "IRL"))

    private fun router(tray: Tray, renderers: Map<PushKind, PushRenderer>, visible: Boolean = false,
                       time: LocalTime = LocalTime.NOON) =
        PushRouter(renderers, tray, appVisible = { visible }, quietAtNight = { true }, now = { time }, timeoutMs = 8_000)

    @Test
    fun `shows the renderer's notice, nothing for something gone, and the generic line when IRL cannot be reached`() = runTest {
        val tray = Tray()
        val r = router(tray, mapOf(
            PushKind.REPLY_TURN to PushRenderer { notice("Asha replied") },
            PushKind.PLAN_ASK to PushRenderer { ApiResult.Failure(ApiError.NOT_FOUND).renderOr { notice("x") } },
            PushKind.MORE_ADDED to PushRenderer { ApiResult.Failure(ApiError.NO_CONNECTION).renderOr { notice("x") } },
            PushKind.PLAN_ADDED to PushRenderer { error("boom") }
        ))
        r.handle(data(PushKind.REPLY_TURN, "00000000-0000-0000-0000-000000000001"))
        r.handle(data(PushKind.PLAN_ASK, "00000000-0000-0000-0000-000000000002"))
        r.handle(data(PushKind.MORE_ADDED, "00000000-0000-0000-0000-000000000003"))
        r.handle(data(PushKind.PLAN_ADDED, "00000000-0000-0000-0000-000000000004"))
        r.handle(data(PushKind.STAFF_WAITING, "00000000-0000-0000-0000-000000000005")) // no renderer
        assertEquals(listOf("REPLY_TURN:Asha replied:sound", "MORE_ADDED:generic", "PLAN_ADDED:generic",
            "STAFF_WAITING:generic"), tray.shown)
    }

    @Test
    fun `a slow read gives way to the generic line after 8 seconds`() = runTest {
        val tray = Tray()
        val r = router(tray, mapOf(PushKind.REPLY_TURN to PushRenderer { delay(20_000); notice("late") }))
        r.handle(data(PushKind.REPLY_TURN))
        assertEquals(listOf("REPLY_TURN:generic"), tray.shown)
    }

    @Test
    fun `the same event twice and anything malformed show nothing`() = runTest {
        val tray = Tray()
        val r = router(tray, mapOf(PushKind.REPLY_TURN to PushRenderer { notice("Asha replied") }))
        r.handle(data(PushKind.REPLY_TURN))
        r.handle(data(PushKind.REPLY_TURN))
        r.handle(mapOf("t" to "REPLY_TURN", "s" to "abc", "m" to "x", "v" to "1"))
        r.handle(mapOf("title" to "Asha", "body" to "hello"))
        assertEquals(1, tray.shown.size)
    }

    @Test
    fun `while IRL is open social pushes only tell the app, safety ones still show`() = runTest {
        val tray = Tray()
        val r = router(tray, mapOf(PushKind.REPLY_TURN to PushRenderer { notice("Asha replied") },
            PushKind.CIRCLE_SOS to PushRenderer { notice("Asha pressed SOS") }), visible = true)
        val events = ArrayList<PushPayload>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { r.events.toList(events) }
        r.handle(data(PushKind.REPLY_TURN, "00000000-0000-0000-0000-000000000001"))
        r.handle(data(PushKind.CIRCLE_SOS, "00000000-0000-0000-0000-000000000002"))
        assertEquals(listOf("CIRCLE_SOS:Asha pressed SOS:sound"), tray.shown)
        assertEquals(listOf(PushKind.REPLY_TURN, PushKind.CIRCLE_SOS), events.map { it.kind })
        job.cancel()
    }

    @Test
    fun `at night social notifications come without sound, an SOS always with sound`() = runTest {
        val tray = Tray()
        val r = router(tray, mapOf(PushKind.REPLY_TURN to PushRenderer { notice("Asha replied") },
            PushKind.CIRCLE_SOS to PushRenderer { notice("SOS") }), time = LocalTime.of(1, 0))
        r.handle(data(PushKind.REPLY_TURN, "00000000-0000-0000-0000-000000000001"))
        r.handle(data(PushKind.CIRCLE_SOS, "00000000-0000-0000-0000-000000000002"))
        assertEquals(listOf("REPLY_TURN:Asha replied:silent", "CIRCLE_SOS:SOS:sound"), tray.shown)
    }

    private class Tokens(override val configured: Boolean = true) : PushTokenSource {
        var token: String? = "tok-1"
        var deleted = 0
        override suspend fun token() = token
        override suspend fun delete() {
            deleted++
        }
    }

    @Test
    fun `registers once per sign-in, again for a new token or sign-in, and forgets at sign-out`() = runTest {
        val tray = Tray()
        val tokens = Tokens()
        val sent = ArrayList<String>()
        var allowed = true
        val registration = PushRegistration(tokens, { token, _ -> sent += token; ApiResult.Success(Unit) }, tray,
            allowed = { allowed }, appVersion = "t")
        val signIn = MutableStateFlow<Pair<Long, String?>?>(1L to "s1")
        registration.follow(backgroundScope, signIn)
        testScheduler.runCurrent()
        assertEquals(listOf("tok-1"), sent)
        registration.sync()
        assertEquals("nothing new", listOf("tok-1"), sent)
        registration.onNewToken("tok-2")
        assertEquals(listOf("tok-1", "tok-2"), sent)

        signIn.value = 2L to "s9" // another account on this phone
        testScheduler.runCurrent()
        assertEquals(1, tray.cancelledAll)
        assertEquals(listOf("tok-1", "tok-2", "tok-2"), sent)

        signIn.value = null
        testScheduler.runCurrent()
        assertEquals(1, tokens.deleted)
        assertEquals(2, tray.cancelledAll)

        allowed = false
        signIn.value = 3L to "s3"
        testScheduler.runCurrent()
        assertEquals("never without the permission", 3, sent.size)
    }

    @Test
    fun `a build without Firebase values never registers or touches the token`() = runTest {
        val tokens = Tokens(configured = false)
        val sent = ArrayList<String>()
        val registration = PushRegistration(tokens, { t, _ -> sent += t; ApiResult.Success(Unit) }, Tray(),
            allowed = { true }, appVersion = null)
        val signIn = MutableStateFlow<Pair<Long, String?>?>(1L to "s1")
        registration.follow(backgroundScope, signIn)
        testScheduler.runCurrent()
        signIn.value = null
        testScheduler.runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(0, tokens.deleted)
    }
}
