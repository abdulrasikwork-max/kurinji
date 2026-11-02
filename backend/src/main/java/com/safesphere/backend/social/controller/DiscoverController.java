package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.DiscoverDtos.MarkResponse;
import com.safesphere.backend.social.dto.DiscoverDtos.SavedPage;
import com.safesphere.backend.social.dto.DiscoverDtos.DiscoverPage;
import com.safesphere.backend.social.dto.DiscoverDtos.Edition;
import com.safesphere.backend.social.dto.DiscoverDtos.PlacePage;
import com.safesphere.backend.social.dto.PostDtos.PostPage;
import com.safesphere.backend.social.service.PostMarkService;
import com.safesphere.backend.social.service.DiscoverService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 13C: Discover. Phase 17E: butterfly reactions and Saved (D-3). Every list ends; more only with an explicit cursor.
 * Invisible = 404.
 */
@RestController
public class DiscoverController {

    private final PostMarkService marks;
    private final DiscoverService discover;

    public DiscoverController(PostMarkService marks, DiscoverService discover) {
        this.marks = marks;
        this.discover = discover;
    }

    @PutMapping("/api/posts/{publicId}/reaction")
    public MarkResponse react(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return marks.react(CurrentUser.id(jwt), publicId);
    }

    @DeleteMapping("/api/posts/{publicId}/reaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unreact(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        marks.unreact(CurrentUser.id(jwt), publicId);
    }

    @PutMapping("/api/posts/{publicId}/save")
    public MarkResponse save(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return marks.save(CurrentUser.id(jwt), publicId);
    }

    @DeleteMapping("/api/posts/{publicId}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsave(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        marks.unsave(CurrentUser.id(jwt), publicId);
    }

    @GetMapping("/api/saved")
    public SavedPage saved(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam(value = "cursor", required = false) String cursor) {
        return marks.savedList(CurrentUser.id(jwt), cursor);
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
