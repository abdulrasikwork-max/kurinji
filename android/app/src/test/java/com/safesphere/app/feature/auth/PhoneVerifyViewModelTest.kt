package com.safesphere.app.feature.auth

import com.safesphere.app.core.validation.FieldError
import com.safesphere.app.feature.auth.domain.AuthError
import com.safesphere.app.feature.auth.domain.AuthResult
import com.safesphere.app.feature.auth.domain.PendingVerification
import com.safesphere.app.feature.auth.domain.PhonePolicy
import com.safesphere.app.feature.auth.ui.AfterVerification
import com.safesphere.app.feature.auth.ui.PhoneVerifyViewModel
import com.safesphere.app.testing.FakeAuthRepository
import com.safesphere.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase 17B: the code screen against an in-memory repository (virtual time for the resend countdown). */
class PhoneVerifyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeAuthRepository()
    private val pending = PendingVerification().apply { begin(FakeAuthRepository.CHALLENGE) }

    private fun viewModel(needsProfile: Boolean = true) = PhoneVerifyViewModel(repository, pending) { needsProfile }

    @Test
    fun `six digits are sent at once, sign in, and a new account goes on to its profile`() = runTest {
        val vm = viewModel(needsProfile = true)
        runCurrent()
        assertEquals("••••••3210", vm.uiState.value.phoneHint)

        vm.onCodeChanged("123 45")
        runCurrent()
        assertEquals(0, repository.verifyCalls) // five digits: nothing sent yet
        vm.onCodeChanged("123 456")
        runCurrent()

        assertEquals("123456", repository.lastVerifiedCode)
        assertEquals(AfterVerification.MAKE_PROFILE, vm.uiState.value.done)
        assertNull(pending.challenge.value) // forgotten once verified
        vm.onDoneHandled()
        assertNull(vm.uiState.value.done)
    }

    @Test
    fun `an account that has a profile goes home`() = runTest {
        val vm = viewModel(needsProfile = false)
        vm.onVerifyClicked("123456")
        runCurrent()
        assertEquals(AfterVerification.HOME, vm.uiState.value.done)
    }

    @Test
    fun `a wrong code shows the server's words and is not sent twice`() = runTest {
        repository.verifyResult = FakeAuthRepository.failure(AuthError.OTP_INVALID, "That code is not right. 4 tries left.")
        val vm = viewModel()
        vm.onCodeChanged("000000")
        runCurrent()
        vm.onCodeChanged("000000")
        runCurrent()

        assertEquals(1, repository.verifyCalls)
        assertEquals(AuthError.OTP_INVALID, vm.uiState.value.authError)
        assertEquals("That code is not right. 4 tries left.", vm.uiState.value.authMessage)
        assertNull(vm.uiState.value.done)
        assertEquals(FakeAuthRepository.CHALLENGE, pending.challenge.value) // still waiting for the right code
    }

    @Test
    fun `an incomplete code is explained instead of sent`() = runTest {
        val vm = viewModel()
        vm.onVerifyClicked("")
        assertEquals(FieldError.REQUIRED, vm.uiState.value.codeError)
        vm.onVerifyClicked("12")
        assertEquals(FieldError.INVALID_CODE, vm.uiState.value.codeError)
        assertEquals(0, repository.verifyCalls)
    }

    @Test
    fun `resend waits for the countdown, then sends and starts it again`() = runTest {
        val vm = viewModel()
        runCurrent()
        assertEquals(60L, vm.uiState.value.resendInSeconds)
        assertFalse(vm.uiState.value.canResend)
        vm.onResendClicked()
        runCurrent()
        assertEquals(0, repository.resendCalls)

        advanceTimeBy(60_001)
        assertEquals(0L, vm.uiState.value.resendInSeconds)
        assertTrue(vm.uiState.value.canResend)
        vm.onResendClicked()
        runCurrent()

        assertEquals(1, repository.resendCalls)
        assertTrue(vm.uiState.value.newCodeSent)
        assertEquals(60L, vm.uiState.value.resendInSeconds)
        vm.onNewCodeShown()
        assertFalse(vm.uiState.value.newCodeSent)
    }

    @Test
    fun `a code that could not be sent can be asked for again at once`() = runTest {
        pending.begin(FakeAuthRepository.CHALLENGE.copy(codeSent = false, resendInSeconds = 0))
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.uiState.value.codeSent)
        assertTrue(vm.uiState.value.canResend)
    }

    @Test
    fun `another number - checked on the phone, then a code goes to it`() = runTest {
        val vm = viewModel()
        vm.onUseAnotherNumber()
        assertTrue(vm.uiState.value.askingForNumber)
        assertTrue(vm.uiState.value.canCancelNumberChange)

        vm.onSubmitNumber("12345")
        assertEquals(FieldError.INVALID_PHONE, vm.uiState.value.phoneError)
        assertNull(repository.lastChangedPhone)

        repository.changeResult = AuthResult.Success(FakeAuthRepository.CHALLENGE.copy(phoneHint = "••••••9999"))
        vm.onSubmitNumber("098765 09999")
        runCurrent()
        assertEquals("+919876509999", repository.lastChangedPhone)
        assertFalse(vm.uiState.value.askingForNumber)
        assertEquals("••••••9999", vm.uiState.value.phoneHint)
    }

    @Test
    fun `an account without a number is asked for one first`() = runTest {
        pending.begin(FakeAuthRepository.CHALLENGE.copy(phoneHint = null, needsPhone = true, codeSent = false,
            resendInSeconds = 0))
        val vm = viewModel()
        assertTrue(vm.uiState.value.askingForNumber)
        assertFalse(vm.uiState.value.canCancelNumberChange)
        vm.onCodeChanged("123456") // no code screen yet: nothing sent
        runCurrent()
        assertEquals(0, repository.verifyCalls)
    }

    @Test
    fun `nothing in memory (the app was closed) - sign in again`() = runTest {
        pending.clear()
        val vm = viewModel()
        assertTrue(vm.uiState.value.lost)
        vm.onCodeChanged("123456")
        runCurrent()
        assertEquals(0, repository.verifyCalls)
    }

    @Test
    fun `an ended request on the server sends the person back to sign in`() = runTest {
        repository.verifyResult = FakeAuthRepository.failure(AuthError.CHALLENGE_INVALID)
        val vm = viewModel()
        vm.onVerifyClicked("123456")
        runCurrent()
        assertTrue(vm.uiState.value.lost)
        assertNull(pending.challenge.value)
    }

    @Test
    fun `the phone rule matches the server's cases`() {
        assertEquals("+919876543210", PhonePolicy.normalize("9876543210"))
        assertEquals("+919876543210", PhonePolicy.normalize("+91 98765 43210"))
        assertEquals("+919876543210", PhonePolicy.normalize("+91-98765-43210"))
        assertEquals("+919876543210", PhonePolicy.normalize("919876543210"))
        assertEquals("+919876543210", PhonePolicy.normalize("09876543210"))
        assertEquals("+919876543210", PhonePolicy.normalize(" (987) 654-3210 "))
        assertNull(PhonePolicy.normalize("12345"))
        assertNull(PhonePolicy.normalize("5876543210"))
        assertNull(PhonePolicy.normalize("+1 415 555 0100"))
        assertNull(PhonePolicy.normalize("+91987654321"))
        assertEquals("123456", PhonePolicy.cleanCode(" 12 34 56 78"))
    }
}
