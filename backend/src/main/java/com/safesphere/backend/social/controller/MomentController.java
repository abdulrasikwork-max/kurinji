package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.MomentDtos.MineResponse;
import com.safesphere.backend.social.dto.MomentDtos.MomentRequest;
import com.safesphere.backend.social.dto.MomentDtos.MomentResponse;
import com.safesphere.backend.social.dto.MomentDtos.TodayResponse;
import com.safesphere.backend.social.service.MomentMediaService;
import com.safesphere.backend.social.service.MomentMediaService.Uploaded;
import com.safesphere.backend.social.service.MomentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final MomentMediaService media;

    public MomentController(MomentService moments, MomentMediaService media) {
        this.moments = moments;
        this.media = media;
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

    /**
     * N4-B2: the photo of a moment. The body of the upload is the image itself (image/jpeg or image/png, at most 5 MB);
     * what is stored and served is always the server's own re-encoded JPEG, never cached in between.
     */
    @PostMapping(path = "/api/moments/media", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    public Uploaded upload(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        return media.upload(CurrentUser.id(jwt), request.getInputStream(), request.getContentLengthLong());
    }

    @DeleteMapping("/api/moments/media/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUpload(@AuthenticationPrincipal Jwt jwt, @PathVariable String mediaId) {
        media.deleteUnattached(CurrentUser.id(jwt), mediaId);
    }

    @GetMapping("/api/moments/{id}/media")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(media.photoOf(CurrentUser.id(jwt), id));
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
