package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.DiscoverDtos.DiscoverPage;
import com.safesphere.backend.social.dto.DiscoverDtos.Edition;
import com.safesphere.backend.social.dto.DiscoverDtos.PlacePage;
import com.safesphere.backend.social.dto.PostDtos.PostPage;
import com.safesphere.backend.social.service.DiscoverService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 13C: Discover. Every list ends; more only with an explicit cursor.
 * Invisible = 404.
 */
@RestController
public class DiscoverController {

    private final DiscoverService discover;

    public DiscoverController(DiscoverService discover) {
        this.discover = discover;
    }

    @GetMapping("/api/discover/edition")
    public Edition edition(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam(value = "city", required = false) String city) {
        return discover.edition(CurrentUser.id(jwt), city);
    }

    @GetMapping("/api/discover/connections")
    public DiscoverPage connections(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(value = "cursor", required = false) String cursor) {
        return discover.connections(CurrentUser.id(jwt), cursor);
    }

    @GetMapping("/api/discover/categories/{category}")
    public PostPage category(@AuthenticationPrincipal Jwt jwt, @PathVariable String category,
                             @RequestParam(value = "city", required = false) String city,
                             @RequestParam(value = "cursor", required = false) String cursor) {
        return discover.category(CurrentUser.id(jwt), category, city, cursor);
    }

    @GetMapping("/api/places/{publicId}/posts")
    public PlacePage placePosts(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                @RequestParam(value = "cursor", required = false) String cursor) {
        return discover.place(CurrentUser.id(jwt), publicId, cursor);
    }
}
