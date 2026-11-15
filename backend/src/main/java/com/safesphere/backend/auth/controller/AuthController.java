package com.safesphere.backend.auth.controller;

import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.ChangeRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.CodeChallengeResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.ResendRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.SignUpRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.StartRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.VerifyRequest;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.VerifyResponse;
import com.safesphere.backend.auth.dto.RefreshRequest;
import com.safesphere.backend.auth.phone.PhoneCodeService;
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
 * (TCP peer, never a forwarded header unless the production proxy setting says so) is passed on only for the
 * in-memory abuse limits. Phase N2: no e-mail and no password anywhere; a phone number and a texted code are the
 * only way in.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final PhoneCodeService phoneCodes;

    public AuthController(AuthService authService, PhoneCodeService phoneCodes) {
        this.authService = authService;
        this.phoneCodes = phoneCodes;
    }

    /** N2: a code for this number; the same answer whether or not it has an account. */
    @PostMapping("/phone/start")
    public CodeChallengeResponse start(@Valid @RequestBody StartRequest request, HttpServletRequest http) {
        return authService.startSignIn(request.phone(), http.getRemoteAddr());
    }

    /** N2: the right code signs in (SIGNED_IN) or, for a number without an account, gives a ticket (SIGN_UP_NEEDED). */
    @PostMapping("/phone/verify")
    public VerifyResponse verify(@Valid @RequestBody VerifyRequest request, HttpServletRequest http) {
        return authService.verify(request, http.getRemoteAddr());
    }

    @PostMapping("/phone/resend")
    public CodeChallengeResponse resendCode(@Valid @RequestBody ResendRequest request, HttpServletRequest http) {
        return phoneCodes.resend(request.challenge(), http.getRemoteAddr());
    }

    /** Another number before the code was entered (a typo). */
    @PostMapping("/phone/change")
    public CodeChallengeResponse changeNumber(@Valid @RequestBody ChangeRequest request, HttpServletRequest http) {
        return phoneCodes.change(request.challenge(), request.phone(), http.getRemoteAddr());
    }

    /** N2: the ticket, a real name and an adult date of birth create the account and sign it in. */
    @PostMapping("/sign-up")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signUp(@Valid @RequestBody SignUpRequest request, HttpServletRequest http) {
        return authService.signUp(request, http.getRemoteAddr());
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
