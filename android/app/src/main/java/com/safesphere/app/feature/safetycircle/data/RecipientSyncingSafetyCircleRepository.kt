package com.safesphere.app.feature.safetycircle.data

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safetycircle.domain.ContactInput
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/**
 * Phase 4D: keeps the phone's offline copy of the Safety Circle (used to send an SMS without internet) in step with
 * the server. Every successful list load passes the contacts on; every successful change reloads the list once.
 * Screens use it exactly like the plain repository.
 */
class RecipientSyncingSafetyCircleRepository(
    private val delegate: SafetyCircleRepository,
    private val onContacts: (List<SafetyContact>) -> Unit
) : SafetyCircleRepository {

    override suspend fun getContacts(): ApiResult<List<SafetyContact>> =
        delegate.getContacts().also { if (it is ApiResult.Success) onContacts(it.data) }

    override suspend fun getContact(id: Long): ApiResult<SafetyContact> = delegate.getContact(id)

    override suspend fun createContact(input: ContactInput): ApiResult<SafetyContact> =
        delegate.createContact(input).also { if (it is ApiResult.Success) getContacts() }

    override suspend fun updateContact(id: Long, input: ContactInput): ApiResult<SafetyContact> =
        delegate.updateContact(id, input).also { if (it is ApiResult.Success) getContacts() }

    override suspend fun deleteContact(id: Long): ApiResult<Unit> =
        delegate.deleteContact(id).also { if (it is ApiResult.Success) getContacts() }
}
