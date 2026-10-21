package com.safesphere.backend.emergency.controller;

import com.safesphere.backend.emergency.dto.CreateEmergencyRequest;
import com.safesphere.backend.emergency.dto.EmergencyResponse;
import com.safesphere.backend.emergency.dto.EndEmergencyRequest;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import com.safesphere.backend.emergency.service.EmergencyService;
import com.safesphere.backend.security.CurrentUser;
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

/** /api/emergencies: records and lists the caller's own emergency sessions (JWT required). */
@RestController
@RequestMapping("/api/emergencies")
public class EmergencyController {

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyResponse start(@AuthenticationPrincipal Jwt jwt,
                                   @RequestBody(required = false) CreateEmergencyRequest request) {
        return service.start(CurrentUser.id(jwt), request == null ? null : request.triggerType(),
                CurrentUser.sessionId(jwt));
    }

    @GetMapping
    public List<EmergencyResponse> history(@AuthenticationPrincipal Jwt jwt) {
        return service.history(CurrentUser.id(jwt));
    }

    @GetMapping("/{id}")
    public EmergencyResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.get(CurrentUser.id(jwt), id);
    }

    /** Phase 10A.4: from another sign-in than the one that started the SOS, the body must hold the password. */
    @PutMapping("/{id}/cancel")
    public EmergencyResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                    @RequestBody(required = false) EndEmergencyRequest request) {
        return service.end(CurrentUser.id(jwt), id, EmergencyStatus.CANCELLED, CurrentUser.sessionId(jwt),
                request == null ? null : request.password());
    }

    @PutMapping("/{id}/resolve")
    public EmergencyResponse resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                     @RequestBody(required = false) EndEmergencyRequest request) {
        return service.end(CurrentUser.id(jwt), id, EmergencyStatus.RESOLVED, CurrentUser.sessionId(jwt),
                request == null ? null : request.password());
    }

    /** Phase 4D, level 4: the person tapped "Call 112" (the phone's dialer opened). Recorded once. */
    @PostMapping("/{id}/dial-prompts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dialPrompt(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.recordDialPrompt(CurrentUser.id(jwt), id);
    }
}
