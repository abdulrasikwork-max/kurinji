package com.safesphere.backend.circlealert.controller;

import com.safesphere.backend.circlealert.dto.CircleAlertDtos.ReportBatch;
import com.safesphere.backend.circlealert.dto.CircleAlertDtos.UploadResult;
import com.safesphere.backend.circlealert.service.CircleAlertService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 4D: the phone reports what it told the Safety Circle about its SOS. JWT required, owner only (another user's
 * emergency answers 404). N6-B1: the walk's report endpoint is gone (walks no longer alert anyone).
 */
@RestController
public class CircleAlertController {

    private final CircleAlertService service;

    public CircleAlertController(CircleAlertService service) {
        this.service = service;
    }

    @PostMapping("/api/emergencies/{id}/circle-alerts")
    public UploadResult reportForEmergency(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                           @Valid @RequestBody ReportBatch batch) {
        return service.uploadForEmergency(CurrentUser.id(jwt), id, batch.reports());
    }
}
