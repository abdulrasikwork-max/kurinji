package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.DiscoverDtos.ButterflyRequest;
import com.safesphere.backend.social.dto.DiscoverDtos.ButterflyResponse;
import com.safesphere.backend.social.dto.DiscoverDtos.CocoonPage;
import com.safesphere.backend.social.dto.DiscoverDtos.DiscoverPage;
import com.safesphere.backend.social.dto.DiscoverDtos.Edition;
import com.safesphere.backend.social.dto.DiscoverDtos.PlacePage;
import com.safesphere.backend.social.dto.PostDtos.PostPage;
import com.safesphere.backend.social.service.ButterflyService;
import com.safesphere.backend.social.service.DiscoverService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 13C: butterflies, the Cocoon and Discover. Every list ends; more only with an explicit cursor. Invisible = 404.
 */
@RestController
public class DiscoverController {

    private final ButterflyService butterflies;
    private final DiscoverService discover;

    public DiscoverController(ButterflyService butterflies, DiscoverService discover) {
        this.butterflies = butterflies;
        this.discover = discover;
    }

    @PutMapping("/api/posts/{publicId}/butterfly")
    public ButterflyResponse butterfly(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                       @Valid @RequestBody(required = false) ButterflyRequest request) {
        return butterflies.put(CurrentUser.id(jwt), publicId, request == null ? null : request.state());
    }

    @DeleteMapping("/api/posts/{publicId}/butterfly")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeButterfly(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        butterflies.remove(CurrentUser.id(jwt), publicId);
    }

    @GetMapping("/api/cocoon")
    public CocoonPage cocoon(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(value = "state", required = false) String state,
                             @RequestParam(value = "cursor", required = false) String cursor) {
        return butterflies.cocoon(CurrentUser.id(jwt), state, cursor);
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
