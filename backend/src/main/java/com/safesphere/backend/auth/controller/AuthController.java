package com.safesphere.backend.auth.controller;

import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.LoginRequest;
import com.safesphere.backend.auth.dto.RefreshRequest;
import com.safesphere.backend.auth.dto.EmailVerificationDtos.ResendVerificationRequest;
import com.safesphere.backend.auth.dto.EmailVerificationDtos.VerificationStatus;
import com.safesphere.backend.auth.dto.EmailVerificationDtos.VerifyEmailRequest;
import com.safesphere.backend.auth.dto.RegisterRequest;
import com.safesphere.backend.auth.dto.RegistrationResponse;
import com.safesphere.backend.auth.service.AuthService;
import com.safesphere.backend.auth.service.EmailVerificationService;
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
    private final EmailVerificationService verification;

    public AuthController(AuthService authService, EmailVerificationService verification) {
        this.authService = authService;
        this.verification = verification;
    }

    /** V25: 201 VERIFICATION_REQUIRED and no session; the account works once the e-mailed link is opened. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, http.getRemoteAddr());
    }

    /** V25: called by the verification page with the token from the link. 200 VERIFIED or one generic 400. */
    @PostMapping("/verify-email")
    public VerificationStatus verifyEmail(@Valid @RequestBody VerifyEmailRequest request, HttpServletRequest http) {
        verification.verify(request.token(), http.getRemoteAddr());
        return VerificationStatus.VERIFIED;
    }

    /** V25: 202 RECEIVED whatever the account's state (sends only for the right password of an unverified account). */
    @PostMapping("/verification/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VerificationStatus resendVerification(@Valid @RequestBody ResendVerificationRequest request,
                                                 HttpServletRequest http) {
        verification.resend(request.email(), request.password(), http.getRemoteAddr());
        return VerificationStatus.RECEIVED;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
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
