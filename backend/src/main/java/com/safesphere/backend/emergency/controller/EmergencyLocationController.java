package com.safesphere.backend.emergency.controller;

import com.safesphere.backend.emergency.dto.LocationBatchRequest;
import com.safesphere.backend.emergency.dto.LocationResponse;
import com.safesphere.backend.emergency.dto.LocationUploadResponse;
import com.safesphere.backend.emergency.service.EmergencyLocationService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/emergencies/{id}/locations: the caller's own emergency locations (JWT required, owner only). */
@RestController
@RequestMapping("/api/emergencies/{id}/locations")
public class EmergencyLocationController {

    private final EmergencyLocationService service;

    public EmergencyLocationController(EmergencyLocationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationUploadResponse upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                         @Valid @RequestBody LocationBatchRequest request) {
        return service.upload(CurrentUser.id(jwt), id, request.points());
    }

    @GetMapping("/latest")
    public LocationResponse latest(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.latest(CurrentUser.id(jwt), id);
    }

    @GetMapping
    public List<LocationResponse> recent(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.recent(CurrentUser.id(jwt), id);
    }
}
