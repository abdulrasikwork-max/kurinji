package com.safesphere.app.feature.safetycircle

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safetycircle.ui.SafetyCircleViewModel
import com.safesphere.app.testing.FakeSafetyCircleRepository
import com.safesphere.app.testing.FakeSafetyCircleRepository.Companion.contact
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SafetyCircleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads the contacts when it starts`() = runTest {
        val repository = FakeSafetyCircleRepository(listOf(contact(1), contact(2, phone = "+911111111111")))
        val viewModel = SafetyCircleViewModel(repository)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.contacts.size)
        assertTrue(state.hasLoaded)
        assertFalse(state.isLoading)
        assertNull(state.failure)
    }

    @Test
    fun `an empty circle is a loaded state, not a loading state`() = runTest {
        val viewModel = SafetyCircleViewModel(FakeSafetyCircleRepository())

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasLoaded)
        assertTrue(viewModel.uiState.value.contacts.isEmpty())
    }

    @Test
    fun `a network failure is reported and nothing crashes`() = runTest {
        val repository = FakeSafetyCircleRepository().apply { failure = ApiResult.Failure(ApiError.NO_CONNECTION) }
        val viewModel = SafetyCircleViewModel(repository)

        advanceUntilIdle()

        assertEquals(ApiError.NO_CONNECTION, viewModel.uiState.value.failure?.error)
        assertFalse(viewModel.uiState.value.hasLoaded)
        assertFalse(viewModel.uiState.value.sessionExpired)
    }

    @Test
    fun `a 401 marks the session as expired`() = runTest {
        val repository = FakeSafetyCircleRepository().apply { failure = ApiResult.Failure(ApiError.UNAUTHORIZED) }
        val viewModel = SafetyCircleViewModel(repository)

        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.sessionExpired)

        viewModel.onSessionExpiredHandled()
        assertFalse(viewModel.uiState.value.sessionExpired)
    }

    @Test
    fun `switching a contact off sends only that change and then reloads`() = runTest {
        val repository = FakeSafetyCircleRepository(listOf(contact(1, primary = false, enabled = true)))
        val viewModel = SafetyCircleViewModel(repository)
        advanceUntilIdle()
        val loadsBefore = repository.getContactsCalls

        viewModel.onEnabledChanged(viewModel.uiState.value.contacts.first(), enabled = false)
        advanceUntilIdle()

        val (id, input) = repository.lastUpdated!!
        assertEquals(1L, id)
        assertFalse(input.isEnabled)
        assertEquals("Asha Devi", input.name)            // everything else is sent unchanged
        assertTrue(repository.getContactsCalls > loadsBefore)
        assertFalse(viewModel.uiState.value.contacts.first().isEnabled)
    }

    @Test
    fun `confirming a delete removes the contact and reloads`() = runTest {
        val repository = FakeSafetyCircleRepository(listOf(contact(1), contact(2, phone = "+911111111111")))
        val viewModel = SafetyCircleViewModel(repository)
        advanceUntilIdle()

        viewModel.onDeleteConfirmed(viewModel.uiState.value.contacts.first())
        advanceUntilIdle()

        assertEquals(1L, repository.lastDeletedId)
        assertEquals(listOf(2L), viewModel.uiState.value.contacts.map { it.id })
    }

    @Test
    fun `a failed delete keeps the list and shows the problem`() = runTest {
        val repository = FakeSafetyCircleRepository(listOf(contact(1)))
        val viewModel = SafetyCircleViewModel(repository)
        advanceUntilIdle()

        repository.failure = ApiResult.Failure(ApiError.SERVER)
        viewModel.onDeleteConfirmed(contact(1))
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.failure)
        assertEquals(ApiError.SERVER, viewModel.uiState.value.failure?.error)
    }
}
