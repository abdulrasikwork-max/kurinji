package com.safesphere.app.feature.safetycircle.data

import com.safesphere.app.core.network.ApiCallHandler
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.safetycircle.data.remote.SafetyContactApi
import com.safesphere.app.feature.safetycircle.data.remote.SafetyContactRequestDto
import com.safesphere.app.feature.safetycircle.domain.ContactInput
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/** Safety Circle stored on the backend. All error handling is in [ApiCallHandler]. */
class RemoteSafetyCircleRepository(
    private val api: SafetyContactApi,
    private val handler: ApiCallHandler
) : SafetyCircleRepository {

    override suspend fun getContacts(): ApiResult<List<SafetyContact>> =
        handler.execute { api.getContacts().map { it.toDomain() } }

    override suspend fun getContact(id: Long): ApiResult<SafetyContact> =
        handler.execute { api.getContact(id).toDomain() }

    override suspend fun createContact(input: ContactInput): ApiResult<SafetyContact> =
        handler.execute { api.createContact(SafetyContactRequestDto.from(input)).toDomain() }

    override suspend fun updateContact(id: Long, input: ContactInput): ApiResult<SafetyContact> =
        handler.execute { api.updateContact(id, SafetyContactRequestDto.from(input)).toDomain() }

    override suspend fun deleteContact(id: Long): ApiResult<Unit> =
        handler.execute { api.deleteContact(id) }
}
