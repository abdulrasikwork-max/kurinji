package com.safesphere.backend.circlealert.controller;

import com.safesphere.backend.circlealert.dto.AlertDtos.StopRequest;
import com.safesphere.backend.circlealert.dto.AlertDtos.StopState;
import com.safesphere.backend.circlealert.service.SafetyAlertService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * N6 (D20): the stop list. A number that asked to receive no IRL alerts is kept only as an HMAC; an admin adds, lifts or
 * checks it by typing the number (ADMIN only: /api/admin/** in SecurityConfig, the role read from the database). The
 * number is never stored, logged or returned. The public request path comes with N8's grievance contact.
 */
@RestController
public class AlertStopAdminController {

    private final SafetyAlertService service;

    public AlertStopAdminController(SafetyAlertService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/alert-stops")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StopRequest request) {
        service.stop(CurrentUser.id(jwt), request.phoneNumber());
    }

    @PostMapping("/api/admin/alert-stops/lift")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lift(@Valid @RequestBody StopRequest request) {
        service.lift(request.phoneNumber());
    }

    @PostMapping("/api/admin/alert-stops/check")
    public StopState check(@Valid @RequestBody StopRequest request) {
        return service.check(request.phoneNumber());
    }
}
