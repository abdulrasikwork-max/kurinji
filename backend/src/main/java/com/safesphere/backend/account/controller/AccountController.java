package com.safesphere.backend.account.controller;

import com.safesphere.backend.account.dto.AccountDtos.ChangePasswordRequest;
import com.safesphere.backend.account.dto.AccountDtos.DeletionScheduled;
import com.safesphere.backend.account.dto.AccountDtos.PasswordConfirmation;
import com.safesphere.backend.account.service.AccountService;
import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.security.CurrentUser;
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

/** Phase 10A: the signed-in person's own account (password, deletion). Only ever the caller's account. */
@RestController
@RequestMapping("/api/users/me")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    /** 200 with a fresh token for this phone; every other session of the account ends. */
    @PutMapping("/password")
    public AuthResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest request) {
        return service.changePassword(CurrentUser.id(jwt), request.currentPassword(), request.newPassword());
    }

    @PostMapping("/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeletionScheduled requestDeletion(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody PasswordConfirmation request) {
        return service.requestDeletion(CurrentUser.id(jwt), request.password());
    }

    @PostMapping("/deletion/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDeletion(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordConfirmation request) {
        service.cancelDeletion(CurrentUser.id(jwt), request.password());
    }
}
