package com.safesphere.app.testing

import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safetycircle.domain.ContactInput
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/** In-memory Safety Circle for ViewModel tests. Set [failure] to make every call fail. */
class FakeSafetyCircleRepository(initial: List<SafetyContact> = emptyList()) : SafetyCircleRepository {

    val contacts = initial.toMutableList()
    var failure: ApiResult.Failure? = null

    var getContactsCalls = 0
        private set
    var createCalls = 0
        private set
    var updateCalls = 0
        private set
    var deleteCalls = 0
        private set
    var lastCreated: ContactInput? = null
        private set
    var lastUpdated: Pair<Long, ContactInput>? = null
        private set
    var lastDeletedId: Long? = null
        private set

    /** A slow network (virtual time): lets tests see what happens while the contact list is loading. */
    var contactsDelayMillis = 0L

    override suspend fun getContacts(): ApiResult<List<SafetyContact>> {
        getContactsCalls++
        if (contactsDelayMillis > 0) kotlinx.coroutines.delay(contactsDelayMillis)
        return failure ?: ApiResult.Success(contacts.toList())
    }

    override suspend fun getContact(id: Long): ApiResult<SafetyContact> {
        failure?.let { return it }
        val found = contacts.firstOrNull { it.id == id } ?: return ApiResult.Failure(ApiError.NOT_FOUND)
        return ApiResult.Success(found)
    }

    override suspend fun createContact(input: ContactInput): ApiResult<SafetyContact> {
        createCalls++
        lastCreated = input
        failure?.let { return it }
        val created = input.toContact(id = (contacts.maxOfOrNull { it.id } ?: 0L) + 1)
        contacts.add(created)
        return ApiResult.Success(created)
    }

    override suspend fun updateContact(id: Long, input: ContactInput): ApiResult<SafetyContact> {
        updateCalls++
        lastUpdated = id to input
        failure?.let { return it }
        val index = contacts.indexOfFirst { it.id == id }
        if (index < 0) return ApiResult.Failure(ApiError.NOT_FOUND)
        val updated = input.toContact(id)
        contacts[index] = updated
        return ApiResult.Success(updated)
    }

    override suspend fun deleteContact(id: Long): ApiResult<Unit> {
        deleteCalls++
        lastDeletedId = id
        failure?.let { return it }
        contacts.removeAll { it.id == id }
        return ApiResult.Success(Unit)
    }

    private fun ContactInput.toContact(id: Long) =
        SafetyContact(id, name, phoneNumber, email, relationship, priority, isPrimary, isEnabled)

    companion object {
        fun contact(
            id: Long = 1,
            name: String = "Asha Devi",
            phone: String = "+919876543210",
            primary: Boolean = false,
            enabled: Boolean = true
        ) = SafetyContact(id, name, phone, null, Relationship.FAMILY, 2, primary, enabled)
    }
}
