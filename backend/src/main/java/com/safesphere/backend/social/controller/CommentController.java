package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.CommentDtos.CommentRequest;
import com.safesphere.backend.social.dto.CommentDtos.CommentResponse;
import com.safesphere.backend.social.service.CommentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phase 13B: Perspectives. Not allowed = not found, everywhere. */
@RestController
public class CommentController {

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/api/posts/{postId}/comments")
    public List<CommentResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable String postId) {
        return comments.list(CurrentUser.id(jwt), postId);
    }

    @PostMapping("/api/posts/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String postId,
                                  @Valid @RequestBody CommentRequest request) {
        return comments.create(CurrentUser.id(jwt), postId, request);
    }

    @DeleteMapping("/api/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String commentId) {
        comments.delete(CurrentUser.id(jwt), commentId);
    }

    @PostMapping("/api/comments/{commentId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@AuthenticationPrincipal Jwt jwt, @PathVariable String commentId) {
        comments.setHidden(CurrentUser.id(jwt), commentId, true);
    }

    @PostMapping("/api/comments/{commentId}/unhide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unhide(@AuthenticationPrincipal Jwt jwt, @PathVariable String commentId) {
        comments.setHidden(CurrentUser.id(jwt), commentId, false);
    }

    @PostMapping("/api/comments/{commentId}/helped")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void helped(@AuthenticationPrincipal Jwt jwt, @PathVariable String commentId) {
        comments.setHelped(CurrentUser.id(jwt), commentId, true);
    }

    @DeleteMapping("/api/comments/{commentId}/helped")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notHelped(@AuthenticationPrincipal Jwt jwt, @PathVariable String commentId) {
        comments.setHelped(CurrentUser.id(jwt), commentId, false);
    }
}
