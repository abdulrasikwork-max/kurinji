package com.safesphere.app.feature.safetycircle

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.safetycircle.ui.ContactFormViewModel
import com.safesphere.app.testing.FakeSafetyCircleRepository
import com.safesphere.app.testing.FakeSafetyCircleRepository.Companion.contact
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContactFormViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSafetyCircleRepository()

    private fun addViewModel() = ContactFormViewModel(repository, contactId = null)

    @Test
    fun `an invalid form shows field errors and never calls the server`() = runTest {
        val viewModel = addViewModel()

        viewModel.onSaveClicked("", "abc", "bad", null, null, isPrimary = false, isEnabled = true)
        advanceUntilIdle()

        val errors = viewModel.uiState.value.errors
        assertEquals(FieldError.REQUIRED, errors.name)
        assertEquals(FieldError.INVALID_PHONE, errors.phone)
        assertEquals(FieldError.INVALID_EMAIL, errors.email)
        assertEquals(FieldError.SELECTION_REQUIRED, errors.relationship)
        assertEquals(0, repository.createCalls)
        assertFalse(viewModel.uiState.value.finished)
    }

    @Test
    fun `a valid new contact is created with trimmed values and the screen finishes`() = runTest {
        val viewModel = addViewModel()

        viewModel.onSaveClicked(
            " Asha Devi ", " +91 98765 43210 ", "  ", Relationship.FAMILY, 2, isPrimary = true, isEnabled = true
        )
        assertTrue(viewModel.uiState.value.isSaving)
        advanceUntilIdle()

        val created = repository.lastCreated!!
        assertEquals("Asha Devi", created.name)
        assertEquals("+91 98765 43210", created.phoneNumber)
        assertNull(created.email)                       // blank email is sent as "no email"
        assertEquals(Relationship.FAMILY, created.relationship)
        assertTrue(created.isPrimary)
        assertTrue(viewModel.uiState.value.finished)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `a disabled contact is never sent as primary`() = runTest {
        val viewModel = addViewModel()

        viewModel.onSaveClicked("Asha", "9876543210", "", Relationship.FRIEND, 3, isPrimary = true, isEnabled = false)
        advanceUntilIdle()

        assertFalse(repository.lastCreated!!.isPrimary)
        assertFalse(repository.lastCreated!!.isEnabled)
    }

    @Test
    fun `a duplicate phone number shows the server's message and does not finish`() = runTest {
        repository.failure = ApiResult.Failure(ApiError.CONFLICT, "This phone number is already in your Safety Circle")
        val viewModel = addViewModel()

        viewModel.onSaveClicked("Asha", "9876543210", "", Relationship.FRIEND, 3, isPrimary = false, isEnabled = true)
        advanceUntilIdle()

        val failure = viewModel.uiState.value.failure
        assertEquals(ApiError.CONFLICT, failure?.error)
        assertEquals("This phone number is already in your Safety Circle", failure?.message)
        assertFalse(viewModel.uiState.value.finished)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `tapping save twice sends one request`() = runTest {
        val viewModel = addViewModel()

        viewModel.onSaveClicked("Asha", "9876543210", "", Relationship.FRIEND, 3, isPrimary = false, isEnabled = true)
        viewModel.onSaveClicked("Asha", "9876543210", "", Relationship.FRIEND, 3, isPrimary = false, isEnabled = true)
        advanceUntilIdle()

        assertEquals(1, repository.createCalls)
    }

    @Test
    fun `editing loads the contact, and saving updates that same id`() = runTest {
        val existing = contact(id = 7, name = "Old Name", phone = "+919000000007")
        val repo = FakeSafetyCircleRepository(listOf(existing))
        val viewModel = ContactFormViewModel(repo, contactId = 7)
        assertTrue(viewModel.isEditing)
        assertTrue(viewModel.uiState.value.isLoadingContact)

        advanceUntilIdle()
        assertEquals("Old Name", viewModel.uiState.value.loadedContact?.name)
        assertFalse(viewModel.uiState.value.isLoadingContact)

        viewModel.onSaveClicked("New Name", "+919000000007", "", Relationship.PARTNER, 1, isPrimary = false, isEnabled = true)
        advanceUntilIdle()

        assertEquals(7L, repo.lastUpdated?.first)
        assertEquals("New Name", repo.lastUpdated?.second?.name)
        assertEquals(0, repo.createCalls)
        assertTrue(viewModel.uiState.value.finished)
    }

    @Test
    fun `deleting after confirmation removes the contact and finishes`() = runTest {
        val repo = FakeSafetyCircleRepository(listOf(contact(id = 7)))
        val viewModel = ContactFormViewModel(repo, contactId = 7)
        advanceUntilIdle()

        viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertEquals(7L, repo.lastDeletedId)
        assertTrue(viewModel.uiState.value.finished)
    }

    @Test
    fun `loading a contact that no longer exists reports not found`() = runTest {
        val viewModel = ContactFormViewModel(FakeSafetyCircleRepository(), contactId = 99)

        advanceUntilIdle()

        assertEquals(ApiError.NOT_FOUND, viewModel.uiState.value.failure?.error)
        assertNull(viewModel.uiState.value.loadedContact)
    }

    @Test
    fun `an expired session during save is reported`() = runTest {
        repository.failure = ApiResult.Failure(ApiError.UNAUTHORIZED)
        val viewModel = addViewModel()

        viewModel.onSaveClicked("Asha", "9876543210", "", Relationship.FRIEND, 3, isPrimary = false, isEnabled = true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sessionExpired)
    }
}
