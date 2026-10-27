package com.safesphere.backend.auth.controller;

import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.LoginRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.ChangeRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.PhoneChallengeResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.ResendRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.StartRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.VerifyRequest;
import com.safesphere.backend.auth.dto.RefreshRequest;
import com.safesphere.backend.auth.dto.RegisterRequest;
import com.safesphere.backend.auth.phone.PhoneVerificationService;
import com.safesphere.backend.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP layer only: receives JSON, validates it with @Valid, delegates to AuthService. The client's network address
 * (TCP peer, never a forwarded header) is passed on only for the in-memory abuse limits.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PhoneVerificationService phoneVerification;

    public AuthController(AuthService authService, PhoneVerificationService phoneVerification) {
        this.authService = authService;
        this.phoneVerification = phoneVerification;
    }

    /** Phase 17B: 201 PHONE_VERIFICATION_REQUIRED and no session; the account works once the texted code is entered. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public PhoneChallengeResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, http.getRemoteAddr());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    /** Phase 17B: e-mail + password of an account without a verified phone (interrupted sign-up, older account). */
    @PostMapping("/phone/start")
    public PhoneChallengeResponse startPhone(@Valid @RequestBody StartRequest request, HttpServletRequest http) {
        return authService.startPhoneVerification(request, http.getRemoteAddr());
    }

    /** Phase 17B: the right code verifies the number and signs in (the same answer as a sign-in). */
    @PostMapping("/phone/verify")
    public AuthResponse verifyPhone(@Valid @RequestBody VerifyRequest request, HttpServletRequest http) {
        return authService.verifyPhone(request, http.getRemoteAddr());
    }

    @PostMapping("/phone/resend")
    public PhoneChallengeResponse resendCode(@Valid @RequestBody ResendRequest request, HttpServletRequest http) {
        return phoneVerification.resend(request.challenge(), http.getRemoteAddr());
    }

    /** Phase 17B: another number before the first one was verified (a typo, a lost SIM). */
    @PostMapping("/phone/change")
    public PhoneChallengeResponse changeNumber(@Valid @RequestBody ChangeRequest request, HttpServletRequest http) {
        return phoneVerification.change(request.challenge(), request.phone(), http.getRemoteAddr());
    }

    /**
     * Phase 11 C1: a new access token and a new refresh secret for a device session. Public on purpose: the access
     * token may have expired long ago; the refresh secret is the credential. 401 + WWW-Authenticate reason if refused.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /** Phase 11 C1: ends this phone's device session on the server. 204 whatever happened (reveals nothing). */
    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(@Valid @RequestBody RefreshRequest request) {
        authService.signOut(request.refreshToken());
    }
}
