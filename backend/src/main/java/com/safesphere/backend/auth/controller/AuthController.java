package com.safesphere.backend.auth.controller;

import com.safesphere.backend.auth.dto.AuthResponse;
import com.safesphere.backend.auth.dto.LoginRequest;
import com.safesphere.backend.auth.dto.RefreshRequest;
import com.safesphere.backend.auth.dto.RegisterRequest;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, http.getRemoteAddr());
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
