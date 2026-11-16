package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.MomentDtos.MineResponse;
import com.safesphere.backend.social.dto.MomentDtos.MomentRequest;
import com.safesphere.backend.social.dto.MomentDtos.MomentResponse;
import com.safesphere.backend.social.dto.MomentDtos.TodayResponse;
import com.safesphere.backend.social.service.MomentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase N4: moments and Today. Identity only from the JWT; every read through the one visibility rule; every route
 * needs an active community membership (403 MEMBERSHIP_NEEDED).
 */
@RestController
public class MomentController {

    private final MomentService moments;

    public MomentController(MomentService moments) {
        this.moments = moments;
    }

    @PostMapping("/api/moments")
    @ResponseStatus(HttpStatus.CREATED)
    public MomentResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MomentRequest request) {
        return moments.create(CurrentUser.id(jwt), request);
    }

    @GetMapping("/api/moments/today")
    public TodayResponse today(@AuthenticationPrincipal Jwt jwt,
                               @RequestParam(value = "people", required = false) String people,
                               @RequestParam(value = "cursor", required = false) String cursor) {
        return moments.today(CurrentUser.id(jwt), people, cursor);
    }

    @GetMapping("/api/moments/mine")
    public MineResponse mine(@AuthenticationPrincipal Jwt jwt) {
        return moments.mine(CurrentUser.id(jwt));
    }

    @GetMapping("/api/moments/{id}")
    public MomentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return moments.get(CurrentUser.id(jwt), id);
    }

    @DeleteMapping("/api/moments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        moments.delete(CurrentUser.id(jwt), id);
    }

    @PutMapping("/api/moments/{id}/kept")
    public MomentResponse keep(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return moments.keep(CurrentUser.id(jwt), id);
    }

    @DeleteMapping("/api/moments/{id}/kept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unkeep(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        moments.unkeep(CurrentUser.id(jwt), id);
    }
}
