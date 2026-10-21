package com.safesphere.app.feature.safetycircle.domain

import com.safesphere.app.core.network.ApiResult

/** The only thing screens know about the Safety Circle's storage: it lives on the server. */
interface SafetyCircleRepository {
    suspend fun getContacts(): ApiResult<List<SafetyContact>>
    suspend fun getContact(id: Long): ApiResult<SafetyContact>
    suspend fun createContact(input: ContactInput): ApiResult<SafetyContact>
    suspend fun updateContact(id: Long, input: ContactInput): ApiResult<SafetyContact>
    suspend fun deleteContact(id: Long): ApiResult<Unit>
}
