package com.safesphere.backend.journey.controller;

import com.safesphere.backend.journey.dto.JourneyResponse;
import com.safesphere.backend.journey.dto.StartJourneyRequest;
import com.safesphere.backend.journey.service.JourneyService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/journeys: the caller's own walks ("On my way", N6 D22). JWT required, owner only; another user's id answers 404.
 * N6-B1: no location upload and no escalation reports (removed with the route / stop checks).
 */
@RestController
@RequestMapping("/api/journeys")
public class JourneyController {

    private final JourneyService service;

    public JourneyController(JourneyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JourneyResponse start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartJourneyRequest request) {
        return service.start(CurrentUser.id(jwt), request);
    }

    @GetMapping
    public List<JourneyResponse> history(@AuthenticationPrincipal Jwt jwt) {
        return service.history(CurrentUser.id(jwt));
    }

    @GetMapping("/{id}")
    public JourneyResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.get(CurrentUser.id(jwt), id);
    }

    @PostMapping("/{id}/check-ins")
    public JourneyResponse checkIn(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.checkIn(CurrentUser.id(jwt), id);
    }

    @PutMapping("/{id}/complete")
    public JourneyResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.complete(CurrentUser.id(jwt), id);
    }

    @PutMapping("/{id}/cancel")
    public JourneyResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.cancel(CurrentUser.id(jwt), id);
    }
}
