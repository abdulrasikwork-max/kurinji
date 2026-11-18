package com.safesphere.backend.circlealert.controller;

import com.safesphere.backend.circlealert.service.CircleSosViewService;
import com.safesphere.backend.circlealert.service.CircleSosViewService.CircleSos;
import com.safesphere.backend.safetycontact.service.CircleLinkService;
import com.safesphere.backend.safetycontact.service.CircleLinkService.Request;
import com.safesphere.backend.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * N7 (D36 = N-128): the Safety Circle in the app, from the member's side. Requests to them, the circles they accepted, and
 * an SOS they were texted about. JWT required; never part of the community gate (safety never depends on social).
 */
@RestController
public class CircleController {

    public record Decline(Boolean dontAskAgain) {
    }

    private final CircleLinkService links;
    private final CircleSosViewService sos;

    public CircleController(CircleLinkService links, CircleSosViewService sos) {
        this.links = links;
        this.sos = sos;
    }

    @GetMapping("/api/circle/requests")
    public List<Request> requests(@AuthenticationPrincipal Jwt jwt) {
        return links.requests(CurrentUser.id(jwt));
    }

    @PostMapping("/api/circle/requests/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        links.accept(CurrentUser.id(jwt), id);
    }

    @PostMapping("/api/circle/requests/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                        @RequestBody(required = false) Decline body) {
        links.decline(CurrentUser.id(jwt), id, body != null && Boolean.TRUE.equals(body.dontAskAgain()));
    }

    @GetMapping("/api/circle/in")
    public List<Request> circlesIn(@AuthenticationPrincipal Jwt jwt) {
        return links.circlesIn(CurrentUser.id(jwt));
    }

    @DeleteMapping("/api/circle/in/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        links.leave(CurrentUser.id(jwt), id);
    }

    @GetMapping("/api/circle/alerts/{emergencyId}")
    public CircleSos alert(@AuthenticationPrincipal Jwt jwt, @PathVariable long emergencyId) {
        return sos.view(CurrentUser.id(jwt), emergencyId);
    }
}
