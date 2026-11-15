package com.safesphere.backend.account.controller;

import com.safesphere.backend.account.dto.AccountDtos.CodeConfirmation;
import com.safesphere.backend.account.dto.AccountDtos.DeletionScheduled;
import com.safesphere.backend.account.dto.AccountDtos.NameChange;
import com.safesphere.backend.account.service.AccountService;
import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.PhoneVerificationDtos.CodeChallengeResponse;
import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in person's own account (Phase 10A; N2: confirmed with a fresh code instead of the password). Only ever
 * the caller's account.
 */
@RestController
@RequestMapping("/api/users/me")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    /** N2: a fresh code to the caller's own number, to confirm one sensitive action (same limits as sign-in). */
    @PostMapping("/confirmation")
    public CodeChallengeResponse startConfirmation(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.startConfirmation(CurrentUser.id(jwt), http.getRemoteAddr());
    }

    /** N2 (N-26): 200 with a fresh token for this phone; every other sign-in of the account ends. */
    @PostMapping("/sessions/sign-out-others")
    public AuthResponse signOutOtherPhones(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody CodeConfirmation request) {
        return service.signOutOtherPhones(CurrentUser.id(jwt), CurrentUser.sessionId(jwt), request.challenge(),
                request.code());
    }

    /** N2 (N-24): the person's real name. */
    @PutMapping("/name")
    public UserResponse changeName(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NameChange request) {
        return service.changeName(CurrentUser.id(jwt), request.name());
    }

    /** Phase 10A.4: 200 with a fresh 24 h token for this sign-in (always allowed during an SOS or Safe Walk). */
    @PostMapping("/token")
    public AuthResponse renewToken(@AuthenticationPrincipal Jwt jwt) {
        return service.renewToken(CurrentUser.id(jwt), CurrentUser.sessionId(jwt), CurrentUser.authTime(jwt));
    }

    @PostMapping("/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeletionScheduled requestDeletion(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody CodeConfirmation request) {
        return service.requestDeletion(CurrentUser.id(jwt), request.challenge(), request.code());
    }

    @PostMapping("/deletion/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeConfirmation request) {
        service.cancelDeletion(CurrentUser.id(jwt), request.challenge(), request.code());
    }
}
