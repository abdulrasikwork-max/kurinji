package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.ProfileDtos.OwnProfileResponse;
import com.safesphere.backend.social.dto.ProfileDtos.PersonProfileResponse;
import com.safesphere.backend.social.dto.ProfileDtos.ProfileRequest;
import com.safesphere.backend.social.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 13A: profiles. Identity only from the JWT; another person's profile only through the one visibility rule. */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/me")
    public OwnProfileResponse own(@AuthenticationPrincipal Jwt jwt) {
        return profiles.own(CurrentUser.id(jwt));
    }

    @PutMapping("/me")
    public OwnProfileResponse save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        return profiles.save(CurrentUser.id(jwt), request);
    }

    @GetMapping("/by-handle/{handle}")
    public PersonProfileResponse byHandle(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle) {
        return profiles.byHandle(CurrentUser.id(jwt), handle);
    }

    @GetMapping("/{publicId}")
    public PersonProfileResponse byPublicId(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return profiles.byPublicId(CurrentUser.id(jwt), publicId);
    }
}
