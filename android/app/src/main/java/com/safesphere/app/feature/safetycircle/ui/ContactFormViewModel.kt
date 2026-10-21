package com.safesphere.app.feature.safetycircle.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.feature.safetycircle.domain.ContactFormErrors
import com.safesphere.app.feature.safetycircle.domain.ContactFormValidator
import com.safesphere.app.feature.safetycircle.domain.ContactInput
import com.safesphere.app.feature.safetycircle.domain.Relationship
import com.safesphere.app.feature.safetycircle.domain.SafetyCircleRepository
import com.safesphere.app.feature.safetycircle.domain.SafetyContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactFormUiState(
    /** Edit mode only: the contact is being fetched. */
    val isLoadingContact: Boolean = false,
    val loadedContact: SafetyContact? = null,
    /** True once the screen has copied [loadedContact] into the fields (so rotation does not overwrite edits). */
    val formPopulated: Boolean = false,
    val errors: ContactFormErrors = ContactFormErrors(),
    val isSaving: Boolean = false,
    val failure: ApiResult.Failure? = null,
    /** True after a successful save or delete: the screen closes. */
    val finished: Boolean = false,
    val sessionExpired: Boolean = false
)

/** [contactId] is null when adding a new contact and the contact's id when editing. */
class ContactFormViewModel(
    private val repository: SafetyCircleRepository,
    private val contactId: Long?
) : ViewModel() {

    val isEditing: Boolean = contactId != null

    private val _uiState = MutableStateFlow(ContactFormUiState(isLoadingContact = contactId != null))
    val uiState: StateFlow<ContactFormUiState> = _uiState.asStateFlow()

    init {
        if (contactId != null) loadContact(contactId)
    }

    private fun loadContact(id: Long) {
        viewModelScope.launch {
            when (val result = repository.getContact(id)) {
                is ApiResult.Success ->
                    _uiState.update { it.copy(isLoadingContact = false, loadedContact = result.data) }
                is ApiResult.Failure ->
                    _uiState.update {
                        it.copy(isLoadingContact = false, failure = result, sessionExpired = result.isSessionExpired())
                    }
            }
        }
    }

    fun onFormPopulated() {
        _uiState.update { it.copy(formPopulated = true) }
    }

    fun onSaveClicked(
        name: String,
        phone: String,
        email: String,
        relationship: Relationship?,
        priority: Int?,
        isPrimary: Boolean,
        isEnabled: Boolean
    ) {
        if (_uiState.value.isSaving) return

        val errors = ContactFormValidator.validate(name, phone, email, relationship, priority)
        val validRelationship = relationship
        val validPriority = priority
        if (errors.hasErrors || validRelationship == null || validPriority == null) {
            _uiState.update { it.copy(errors = errors, failure = null) }
            return
        }

        val input = ContactInput(
            name = name.trim(),
            phoneNumber = phone.trim(),
            email = email.trim().ifEmpty { null },
            relationship = validRelationship,
            priority = validPriority,
            // A disabled contact cannot be primary (the server enforces this too).
            isPrimary = isPrimary && isEnabled,
            isEnabled = isEnabled
        )
        _uiState.update { it.copy(errors = ContactFormErrors(), isSaving = true, failure = null) }

        viewModelScope.launch {
            val result = if (contactId == null) {
                repository.createContact(input)
            } else {
                repository.updateContact(contactId, input)
            }
            finishWith(result)
        }
    }

    /** Called only after the person confirmed the delete dialog. */
    fun onDeleteConfirmed() {
        val id = contactId ?: return
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, failure = null) }
        viewModelScope.launch { finishWith(repository.deleteContact(id)) }
    }

    fun onSessionExpiredHandled() {
        _uiState.update { it.copy(sessionExpired = false) }
    }

    private fun finishWith(result: ApiResult<*>) {
        _uiState.update {
            when (result) {
                is ApiResult.Success -> it.copy(isSaving = false, finished = true)
                is ApiResult.Failure ->
                    it.copy(isSaving = false, failure = result, sessionExpired = result.isSessionExpired())
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, contactId: Long?) = viewModelFactory {
            initializer { ContactFormViewModel(container.safetyCircleRepository, contactId) }
        }
    }
}
