package com.safesphere.app.feature.safetycircle

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safetycircle.data.CircleRequest
import com.safesphere.app.feature.safetycircle.data.CircleRequestDto
import com.safesphere.app.feature.safetycircle.data.CircleSos
import com.safesphere.app.feature.safetycircle.data.CircleSosDto
import com.safesphere.app.feature.safetycircle.data.CircleSosStatus
import com.safesphere.app.feature.safetycircle.data.remote.SafetyContactDto
import com.safesphere.app.feature.safetycircle.ui.CircleSosState
import com.safesphere.app.feature.safetycircle.ui.CircleSosViewModel
import com.safesphere.app.feature.safetycircle.ui.CirclesState
import com.safesphere.app.feature.safetycircle.ui.CirclesViewModel
import com.safesphere.app.testing.MainDispatcherRule
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** N7 (D36): the Safety Circle in the app on the phone. */
class CircleInAppTest {

    @get:Rule val mainRule = MainDispatcherRule()

    private val uuid = "0b6f6a39-2f8e-4c5e-9a43-111122223333"

    @Test
    fun `requests fail closed`() {
        assertEquals(CircleRequest(uuid, "Asha"), CircleRequestDto(uuid, " Asha ").toDomain())
        assertNull(CircleRequestDto("not-a-uuid", "Asha").toDomain())
        assertNull(CircleRequestDto(uuid, " ").toDomain())
        assertNull(CircleRequestDto(null, "Asha").toDomain())
    }

    @Test
    fun `an SOS view fails closed - unknown status reads as ended, only an Indian mobile and IRL's map link`() {
        val good = CircleSosDto("Asha", "+919876543210", "2026-11-18T10:31:35Z", "ACTIVE",
            "https://maps.google.com/?q=10.81234,78.69012", "2026-11-18T10:31:35Z", false).toDomain()!!
        assertEquals(CircleSosStatus.ACTIVE, good.status)
        assertEquals("+919876543210", good.phoneE164)
        val odd = CircleSosDto("Asha", "+14155550100", "2026-11-18T10:31:35Z", "PANIC",
            "https://evil.example/?q=1,2", null, null).toDomain()!!
        assertEquals(CircleSosStatus.ENDED, odd.status)
        assertNull(odd.phoneE164)
        assertNull(odd.mapLink)
        assertFalse(odd.sentLate)
        assertNull(CircleSosDto(null, null, "2026-11-18T10:31:35Z", "ACTIVE", null, null, false).toDomain())
        assertNull(CircleSosDto("Asha", null, "yesterday", "ACTIVE", null, null, false).toDomain())
        assertFalse("no location in logs", good.toString().contains("10.81"))
    }

    @Test
    fun `a contact says Also in the IRL app only when the server says so`() {
        fun dto(inApp: Boolean?) = SafetyContactDto(1, "Bala", "+919876543210", null, "FRIEND", 1, false, true, inApp)
        assertTrue(dto(true).toDomain().inApp)
        assertFalse(dto(false).toDomain().inApp)
        assertFalse("an older server", dto(null).toDomain().inApp)
    }

    @Test
    fun `accepting offers the notification card and reloads, a failure says so`() = runTest {
        var requests = listOf(CircleRequest(uuid, "Asha"))
        var circles = emptyList<CircleRequest>()
        var offered = 0
        var fail = false
        val vm = CirclesViewModel(
            requests = { ApiResult.Success(requests) },
            circlesIn = { ApiResult.Success(circles) },
            accept = { id -> if (fail) ApiResult.Failure(ApiError.NO_CONNECTION) else { requests = emptyList(); circles = listOf(CircleRequest(id, "Asha")); ApiResult.Success(Unit) } },
            decline = { _, _ -> ApiResult.Success(Unit) },
            leave = { ApiResult.Success(Unit) },
            onAccepted = { offered++ }
        )
        vm.load()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.requests.size)
        vm.onAccept(uuid)
        advanceUntilIdle()
        assertEquals(1, offered)
        assertTrue(vm.state.value.requests.isEmpty())
        assertEquals(1, vm.state.value.circles.size)

        fail = true
        requests = listOf(CircleRequest(uuid, "Asha"))
        vm.load()
        advanceUntilIdle()
        vm.onAccept(uuid)
        advanceUntilIdle()
        assertEquals(CirclesState.Problem.ACTION, vm.state.value.problem)
        assertEquals(1, offered)
    }

    @Test
    fun `the SOS screen follows an active SOS until it ends, and a gone one says so`() = runTest {
        var status = CircleSosStatus.ACTIVE
        var reads = 0
        val vm = CircleSosViewModel { _ ->
            reads++
            ApiResult.Success(CircleSos("Asha", null, Instant.parse("2026-11-18T10:00:00Z"), status, null, null, false))
        }
        vm.watch(5)
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(CircleSosViewModel.REFRESH_MS + 1)
        assertEquals(2, reads)
        status = CircleSosStatus.SAFE
        advanceTimeBy(CircleSosViewModel.REFRESH_MS + 1)
        assertEquals(3, reads)
        advanceTimeBy(CircleSosViewModel.REFRESH_MS * 3)
        assertEquals("stops once it ended", 3, reads)
        vm.stop()

        val gone = CircleSosViewModel { _ -> ApiResult.Failure(ApiError.NOT_FOUND) }
        gone.watch(5)
        runCurrent()
        assertEquals(CircleSosState.Gone, gone.state.value)
    }

    @Test
    fun `the SOS from your people screen wears the emergency treatment and never more than the text`() {
        val layout = File("src/main/res/layout/fragment_circle_sos.xml").readText()
        assertTrue(layout.contains("Widget.SafeSphere.Card.Emergency"))
        assertTrue(layout.contains("@drawable/ic_siren"))
        val renderers = File("src/main/java/com/safesphere/app/feature/safetycircle/push/CirclePushRenderers.kt").readText()
        assertFalse("no location in a notification", renderers.contains("mapLink") || renderers.contains("locationAt"))
        val api = File("src/main/java/com/safesphere/app/feature/safetycircle/data/CircleInApp.kt").readText()
        assertFalse("nothing about other people stored", api.contains("DataStore") || api.contains("SharedPreferences"))
    }
}
