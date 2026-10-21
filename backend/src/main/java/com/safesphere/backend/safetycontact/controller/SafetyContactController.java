package com.safesphere.backend.safetycontact.controller;

import com.safesphere.backend.safetycontact.dto.SafetyContactRequest;
import com.safesphere.backend.safetycontact.dto.SafetyContactResponse;
import com.safesphere.backend.safetycontact.service.SafetyContactService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/safety-contacts. SecurityConfig already requires a valid JWT for every URL here. Each method
 * passes the caller's id (from the token) to the service; the client can never choose it.
 */
@RestController
@RequestMapping("/api/safety-contacts")
public class SafetyContactController {

    private final SafetyContactService service;

    public SafetyContactController(SafetyContactService service) {
        this.service = service;
    }

    @GetMapping
    public List<SafetyContactResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentUser.id(jwt));
    }

    @GetMapping("/{id}")
    public SafetyContactResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.get(CurrentUser.id(jwt), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SafetyContactResponse create(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody SafetyContactRequest request) {
        return service.create(CurrentUser.id(jwt), request);
    }

    @PutMapping("/{id}")
    public SafetyContactResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                        @Valid @RequestBody SafetyContactRequest request) {
        return service.update(CurrentUser.id(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(CurrentUser.id(jwt), id);
    }
}
