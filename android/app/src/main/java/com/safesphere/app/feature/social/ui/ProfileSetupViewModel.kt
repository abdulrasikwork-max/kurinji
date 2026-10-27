package com.safesphere.app.feature.social.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.AgeGate
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.network.isSessionExpired
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.social.domain.AgePolicy
import com.safesphere.app.feature.social.domain.OwnProfile
import com.safesphere.app.feature.social.domain.ProfileInput
import com.safesphere.app.feature.social.domain.ProfileInputPolicy
import com.safesphere.app.feature.social.domain.SocialRepository
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileSetupUiState(
    val isLoading: Boolean = true,
    /** An account from before 13A has not declared an adult date of birth yet (asked once, here). */
    val needsAgeConfirmation: Boolean = false,
    val dateOfBirth: LocalDate? = null,
    val dateOfBirthError: FieldError? = null,
    val profile: OwnProfile? = null,
    /** True once the profile (or "none yet") was loaded: the form is shown, and stays after a failed save. */
    val formReady: Boolean = false,
    /** The loaded profile, handed to the form once to fill its fields; null after that. */
    val prefill: OwnProfile? = null,
    val problems: ProfileInputPolicy.Problems = ProfileInputPolicy.Problems(),
    val isSaving: Boolean = false,
    /** Phase 17A, one-shot: the age was just confirmed here (the "age only" screen then closes). */
    val ageJustConfirmed: Boolean = false,
    /** One-shot: the profile was saved. */
    val saved: Boolean = false,
    val failure: ApiResult.Failure? = null,
    val sessionExpired: Boolean = false
)

/**
 * Phase 13A: create or edit one's profile. An account created before 13A first confirms an adult date of birth (the
 * same 18+ rule as sign-up; the date is sent once and stored nowhere). The form is checked on the phone with the same
 * rules as the server, which decides.
 */
class ProfileSetupViewModel(
    private val repository: SocialRepository,
    private val clock: Clock = Clock.systemUTC(),
    /** Phase 17A: the app-wide age state, kept in step with what this screen learns. */
    private val ageGate: AgeGate = AgeGate()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileSetupUiState())
    val uiState: StateFlow<ProfileSetupUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun today(): LocalDate = AgePolicy.today(clock)

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val age = repository.ageConfirmed()) {
                is ApiResult.Failure -> return@launch fail(age)
                is ApiResult.Success -> if (!age.data) {
                    ageGate.onRefused()
                    _uiState.update { it.copy(isLoading = false, needsAgeConfirmation = true) }
                    return@launch
                } else {
                    ageGate.onConfirmed()
                }
            }
            when (val result = repository.ownProfile()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoading = false, needsAgeConfirmation = false, profile = result.data, prefill = result.data,
                        formReady = true, failure = null)
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onDateOfBirthChosen(date: LocalDate) {
        _uiState.update { it.copy(dateOfBirth = date, dateOfBirthError = ageError(date)) }
    }

    fun onConfirmAge() {
        val date = _uiState.value.dateOfBirth
        val error = ageError(date)
        if (error != null || date == null) {
            _uiState.update { it.copy(dateOfBirthError = error ?: FieldError.REQUIRED) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = repository.confirmAge(date)) {
                is ApiResult.Success -> {
                    ageGate.onConfirmed()
                    _uiState.update { it.copy(isSaving = false, dateOfBirth = null, ageJustConfirmed = true) }
                    load()
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onSave(input: ProfileInput) {
        if (_uiState.value.isSaving) return
        val problems = ProfileInputPolicy.check(input)
        _uiState.update { it.copy(problems = problems, failure = null) }
        if (problems.any) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = repository.saveProfile(input)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isSaving = false, profile = result.data, saved = true)
                }
                is ApiResult.Failure -> fail(result)
            }
        }
    }

    fun onPrefilled() = _uiState.update { it.copy(prefill = null) }

    fun onSavedShown() = _uiState.update { it.copy(saved = false) }

    fun onAgeConfirmedShown() = _uiState.update { it.copy(ageJustConfirmed = false) }

    fun onSessionExpiredHandled() = _uiState.update { it.copy(sessionExpired = false) }

    private fun fail(failure: ApiResult.Failure) {
        _uiState.update {
            it.copy(isLoading = false, isSaving = false, failure = failure, sessionExpired = failure.isSessionExpired())
        }
    }

    private fun ageError(date: LocalDate?): FieldError? = when (AgePolicy.check(date, today())) {
        AgePolicy.Result.ADULT -> null
        AgePolicy.Result.UNDER_AGE -> FieldError.UNDER_AGE
        AgePolicy.Result.INVALID -> if (date == null) FieldError.REQUIRED else FieldError.INVALID_DATE_OF_BIRTH
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ProfileSetupViewModel(container.socialRepository, ageGate = container.ageGate) }
        }
    }
}
