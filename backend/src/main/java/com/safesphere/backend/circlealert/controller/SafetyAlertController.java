package com.safesphere.backend.circlealert.controller;

import com.safesphere.backend.circlealert.dto.AlertDtos.Choice;
import com.safesphere.backend.circlealert.dto.AlertDtos.EmergencyAlerts;
import com.safesphere.backend.circlealert.dto.AlertDtos.Settings;
import com.safesphere.backend.circlealert.service.SafetyAlertService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * N6 (D20): the person's automatic Safety Circle texts. JWT required; everything is the caller's own (another person's
 * emergency answers 404). Never part of the community gate: safety works without a membership.
 */
@RestController
public class SafetyAlertController {

    private final SafetyAlertService service;

    public SafetyAlertController(SafetyAlertService service) {
        this.service = service;
    }

    @GetMapping("/api/safety-alerts")
    public Settings settings(@AuthenticationPrincipal Jwt jwt) {
        return service.settings(CurrentUser.id(jwt));
    }

    @PutMapping("/api/safety-alerts")
    public Settings choose(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Choice choice) {
        return service.choose(CurrentUser.id(jwt), choice.on());
    }

    @PostMapping("/api/safety-alerts/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Settings test(@AuthenticationPrincipal Jwt jwt) {
        return service.test(CurrentUser.id(jwt));
    }

    @GetMapping("/api/emergencies/{id}/alerts")
    public EmergencyAlerts forEmergency(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.forEmergency(CurrentUser.id(jwt), id);
    }
}
