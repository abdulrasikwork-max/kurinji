package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.ExploreDtos.ExploreRequest;
import com.safesphere.backend.social.dto.ExploreDtos.ExploreResponse;
import com.safesphere.backend.social.service.ExploreService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 17G-1: Explore. One POST with a structured request (never free text); answers PLACES. Identity only from the JWT.
 * It is a POST so the request is never part of a URL (and so of a log), not because it changes anything: it reads only.
 */
@RestController
public class ExploreController {

    private final ExploreService explore;

    public ExploreController(ExploreService explore) {
        this.explore = explore;
    }

    @PostMapping("/api/explore/search")
    public ExploreResponse search(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ExploreRequest request) {
        return explore.search(CurrentUser.id(jwt), request);
    }
}
