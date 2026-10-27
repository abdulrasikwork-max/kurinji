package com.safesphere.app.feature.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.AppContainer
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthRepository
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.auth.domain.PhoneChallenge
import com.safesphere.app.feature.auth.domain.PhonePolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Phase 17B: where the code screen goes once the phone is verified (and this phone is signed in). */
enum class AfterVerification { MAKE_PROFILE, HOME }

data class PhoneVerifyUiState(
    /** The last 4 digits of the number the code went to ("••••••3210"); null while no number is known. */
    val phoneHint: String? = null,
    /** The number field is shown: the account has none yet, or the person chose "Use another number". */
    val askingForNumber: Boolean = false,
    /** Whether a number exists to go back to from "Use another number". */
    val canCancelNumberChange: Boolean = false,
    /** A code is waiting at the number (false = it could not be sent: "Send a new code" right away). */
    val codeSent: Boolean = false,
    /** Seconds until "Send a new code" works; counted down on the phone from the server's figure. */
    val resendInSeconds: Long = 0,
    val isBusy: Boolean = false,
    val codeError: FieldError? = null,
    val phoneError: FieldError? = null,
    val authError: AuthError? = null,
    /** The server's own words ("2 tries left", "in 40 seconds"). */
    val authMessage: String? = null,
    /** One-shot: a new code was sent. */
    val newCodeSent: Boolean = false,
    /**
     * Nothing to verify in this app's memory (the app was closed, or the code request ended on the server): the person
     * signs in again, which resumes the same verification.
     */
    val lost: Boolean = false,
    /** One-shot: verified and signed in; where to go. */
    val done: AfterVerification? = null
) {
    val canResend: Boolean get() = !askingForNumber && phoneHint != null && resendInSeconds == 0L && !isBusy
}

/**
 * Phase 17B: the "Enter the code" screen. Reads the verification handed over by sign-up or sign-in
 * ([PendingVerification], memory only). Six digits are sent as soon as they are typed (once per code); wrong, expired
 * and locked codes, resend (with the server's cooldown counted down here) and a change of number are all on this screen.
 * Nothing is stored on the phone; a successful code signs in exactly like a sign-in.
 */
class PhoneVerifyViewModel(
    private val authRepository: AuthRepository,
    private val pending: PendingVerification,
    /** True when the signed-in account has no profile yet (then the handle screen comes next). */
    private val needsProfile: suspend () -> Boolean = { false }
) : ViewModel() {

    private val _uiState = MutableStateFlow(PhoneVerifyUiState())
    val uiState: StateFlow<PhoneVerifyUiState> = _uiState.asStateFlow()

    private var countdown: Job? = null
    private var lastSubmittedCode: String? = null

    init {
        val challenge = pending.challenge.value
        if (challenge == null) _uiState.update { it.copy(lost = true) } else show(challenge)
    }

    /** Called as the person types; a complete code is sent at once (the same wrong code is not sent twice). */
    fun onCodeChanged(raw: String) {
        val code = PhonePolicy.cleanCode(raw)
        _uiState.update { it.copy(codeError = null) }
        if (code.length == PhonePolicy.CODE_LENGTH && code != lastSubmittedCode) verify(code)
    }

    /** The "Verify" button: also says what is missing. */
    fun onVerifyClicked(raw: String) {
        val code = PhonePolicy.cleanCode(raw)
        if (code.length != PhonePolicy.CODE_LENGTH) {
            _uiState.update { it.copy(codeError = if (code.isEmpty()) FieldError.REQUIRED else FieldError.INVALID_CODE) }
            return
        }
        verify(code)
    }

    fun onResendClicked() {
        val challenge = pending.challenge.value ?: return lose()
        if (!_uiState.value.canResend) return
        _uiState.update { it.copy(isBusy = true, authError = null, authMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.resendCode(challenge)) {
                is AuthResult.Success -> {
                    lastSubmittedCode = null
                    show(result.data)
                    _uiState.update { it.copy(newCodeSent = result.data.codeSent) }
                }
                is AuthResult.Failure -> fail(result)
            }
        }
    }

    fun onUseAnotherNumber() {
        _uiState.update {
            it.copy(askingForNumber = true, canCancelNumberChange = it.phoneHint != null, phoneError = null,
                authError = null, authMessage = null)
        }
    }

    fun onCancelNumberChange() {
        if (_uiState.value.phoneHint == null) return
        _uiState.update { it.copy(askingForNumber = false, phoneError = null) }
    }

    fun onSubmitNumber(raw: String) {
        val challenge = pending.challenge.value ?: return lose()
        val phone = PhonePolicy.normalize(raw)
        if (phone == null) {
            _uiState.update { it.copy(phoneError = if (raw.isBlank()) FieldError.REQUIRED else FieldError.INVALID_PHONE) }
            return
        }
        _uiState.update { it.copy(isBusy = true, phoneError = null, authError = null, authMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.changePhone(challenge, phone)) {
                is AuthResult.Success -> {
                    lastSubmittedCode = null
                    show(result.data)
                    _uiState.update { it.copy(newCodeSent = result.data.codeSent) }
                }
                is AuthResult.Failure -> if (result.error == AuthError.PHONE_INVALID) {
                    _uiState.update { it.copy(isBusy = false, phoneError = FieldError.INVALID_PHONE) }
                } else {
                    fail(result)
                }
            }
        }
    }

    fun onNewCodeShown() = _uiState.update { it.copy(newCodeSent = false) }

    fun onDoneHandled() = _uiState.update { it.copy(done = null) }

    private fun verify(code: String) {
        if (_uiState.value.isBusy || _uiState.value.askingForNumber) return
        val challenge = pending.challenge.value ?: return lose()
        lastSubmittedCode = code
        _uiState.update { it.copy(isBusy = true, codeError = null, authError = null, authMessage = null) }
        viewModelScope.launch {
            when (val result = authRepository.verifyPhone(challenge, code)) {
                is AuthResult.Success -> {
                    pending.clear()
                    countdown?.cancel()
                    val next = if (needsProfile()) AfterVerification.MAKE_PROFILE else AfterVerification.HOME
                    _uiState.update { it.copy(isBusy = false, done = next) }
                }
                is AuthResult.Failure -> {
                    // A new attempt at the same digits makes sense only after a connection problem.
                    if (result.error == AuthError.NO_CONNECTION || result.error == AuthError.SERVER) lastSubmittedCode = null
                    if (result.error == AuthError.PHONE_REQUIRED) onUseAnotherNumber()
                    fail(result)
                }
            }
        }
    }

    private fun show(challenge: PhoneChallenge) {
        pending.update(challenge)
        _uiState.update {
            it.copy(phoneHint = challenge.phoneHint, askingForNumber = challenge.needsPhone,
                canCancelNumberChange = false, codeSent = challenge.codeSent, resendInSeconds = challenge.resendInSeconds,
                isBusy = false, lost = false)
        }
        startCountdown()
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (_uiState.value.resendInSeconds > 0) {
                delay(ONE_SECOND)
                _uiState.update { it.copy(resendInSeconds = (it.resendInSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    private fun fail(result: AuthResult.Failure) {
        if (result.error == AuthError.CHALLENGE_INVALID) return lose(result)
        _uiState.update { it.copy(isBusy = false, authError = result.error, authMessage = result.message) }
    }

    /** The verification is gone (app closed, or ended on the server): sign in again to resume it. */
    private fun lose(result: AuthResult.Failure? = null) {
        pending.clear()
        countdown?.cancel()
        _uiState.update {
            it.copy(isBusy = false, lost = true, authError = result?.error ?: AuthError.CHALLENGE_INVALID,
                authMessage = null)
        }
    }

    companion object {
        const val ONE_SECOND = 1_000L

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                PhoneVerifyViewModel(container.authRepository, container.pendingVerification) {
                    val profile = container.socialRepository.ownProfile()
                    profile is ApiResult.Success && profile.data == null
                }
            }
        }
    }
}
