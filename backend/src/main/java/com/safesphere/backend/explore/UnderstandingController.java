package com.safesphere.backend.explore;

import com.safesphere.backend.explore.UnderstandingDtos.UnderstandRequest;
import com.safesphere.backend.explore.UnderstandingDtos.UnderstandResponse;
import com.safesphere.backend.explore.UnderstandingDtos.UnderstandingStatus;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 17G-3: the optional understanding of a typed Explore request. A POST so the words are never part of a URL (and so of
 * a log). Identity only from the JWT. Both endpoints are harmless to ignore: the app works without them.
 */
@RestController
public class UnderstandingController {

    private final UnderstandingService understanding;

    public UnderstandingController(UnderstandingService understanding) {
        this.understanding = understanding;
    }

    @PostMapping("/api/explore/understand")
    public UnderstandResponse understand(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UnderstandRequest request) {
        return understanding.understand(CurrentUser.id(jwt), request.text());
    }

    @GetMapping("/api/explore/understanding")
    public UnderstandingStatus status() {
        return understanding.status();
    }
}
