package com.safesphere.backend.journey.controller;

import com.safesphere.backend.journey.dto.EscalationRequest;
import com.safesphere.backend.journey.dto.JourneyLocationRequest;
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

/** /api/journeys: the caller's own Safe Walks (JWT required, owner only; another user's id answers 404). */
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

    @PostMapping("/{id}/escalations")
    public JourneyResponse escalate(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                    @Valid @RequestBody EscalationRequest request) {
        return service.escalate(CurrentUser.id(jwt), id, request.level(), request.reason());
    }

    @PutMapping("/{id}/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateLocation(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                               @Valid @RequestBody JourneyLocationRequest request) {
        service.updateLocation(CurrentUser.id(jwt), id, request);
    }
}
