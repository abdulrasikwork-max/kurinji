package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.PostDtos.PostEditRequest;
import com.safesphere.backend.social.dto.PostDtos.PostPage;
import com.safesphere.backend.social.dto.PostDtos.PostRequest;
import com.safesphere.backend.social.dto.PostDtos.PostResponse;
import com.safesphere.backend.social.service.PostService;
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

/** Phase 13B: Experiences and Asks. Identity only from the JWT; every read through the one visibility rule. */
@RestController
public class PostController {

    private final PostService posts;

    public PostController(PostService posts) {
        this.posts = posts;
    }

    @PostMapping("/api/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PostRequest request) {
        return posts.create(CurrentUser.id(jwt), request);
    }

    @GetMapping("/api/posts/mine")
    public PostPage mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(value = "cursor", required = false) String cursor) {
        return posts.mine(CurrentUser.id(jwt), cursor);
    }

    @GetMapping("/api/posts/{publicId}")
    public PostResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return posts.get(CurrentUser.id(jwt), publicId);
    }

    @PutMapping("/api/posts/{publicId}")
    public PostResponse edit(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                             @Valid @RequestBody PostEditRequest request) {
        return posts.edit(CurrentUser.id(jwt), publicId, request);
    }

    @DeleteMapping("/api/posts/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        posts.delete(CurrentUser.id(jwt), publicId);
    }

    @GetMapping("/api/profiles/{profileId}/posts")
    public PostPage byProfile(@AuthenticationPrincipal Jwt jwt, @PathVariable String profileId,
                              @RequestParam(value = "cursor", required = false) String cursor) {
        return posts.byProfile(CurrentUser.id(jwt), profileId, cursor);
    }
}
