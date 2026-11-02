package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.ProfileDtos.DailyStateRequest;
import com.safesphere.backend.social.dto.ProfileDtos.OwnProfileResponse;
import com.safesphere.backend.social.dto.ProfileDtos.PersonProfileResponse;
import com.safesphere.backend.social.dto.ProfileDtos.ProfileRequest;
import com.safesphere.backend.social.service.DailyStateService;
import com.safesphere.backend.social.service.ProfilePhotoService;
import com.safesphere.backend.social.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 13A: profiles. Identity only from the JWT; another person's profile only through the one visibility rule.
 * Phase 17E: the profile photo (served only to people who may see the profile) and today's state (D-7).
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService profiles;
    private final ProfilePhotoService photos;
    private final DailyStateService dailyStates;

    public ProfileController(ProfileService profiles, ProfilePhotoService photos, DailyStateService dailyStates) {
        this.profiles = profiles;
        this.photos = photos;
        this.dailyStates = dailyStates;
    }

    @GetMapping("/me")
    public OwnProfileResponse own(@AuthenticationPrincipal Jwt jwt) {
        return profiles.own(CurrentUser.id(jwt));
    }

    @PutMapping("/me")
    public OwnProfileResponse save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        return profiles.save(CurrentUser.id(jwt), request);
    }

    @PutMapping(path = "/me/photo", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    public Map<String, String> uploadPhoto(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request)
            throws IOException {
        return Map.of("photo", photos.upload(CurrentUser.id(jwt), request.getInputStream()));
    }

    @DeleteMapping("/me/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePhoto(@AuthenticationPrincipal Jwt jwt) {
        photos.remove(CurrentUser.id(jwt));
    }

    @PutMapping("/me/daily-state")
    public Map<String, String> setDailyState(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody DailyStateRequest request) {
        return Map.of("dailyState", dailyStates.set(CurrentUser.id(jwt), request.state()));
    }

    @DeleteMapping("/me/daily-state")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearDailyState(@AuthenticationPrincipal Jwt jwt) {
        dailyStates.clear(CurrentUser.id(jwt));
    }

    @GetMapping("/by-handle/{handle}")
    public PersonProfileResponse byHandle(@AuthenticationPrincipal Jwt jwt, @PathVariable String handle) {
        return profiles.byHandle(CurrentUser.id(jwt), handle);
    }

    @GetMapping("/{publicId}")
    public PersonProfileResponse byPublicId(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return profiles.byPublicId(CurrentUser.id(jwt), publicId);
    }

    @GetMapping("/{publicId}/photo/{key}")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                        @PathVariable String key) {
        return image(photos.photo(CurrentUser.id(jwt), publicId, key));
    }

    /** The same headers as every other image: never stored by a cache, never sniffed. */
    public static ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(bytes);
    }
}
