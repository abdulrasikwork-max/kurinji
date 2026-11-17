package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.RepliesDtos.Marker;
import com.safesphere.backend.social.dto.RepliesDtos.RepliesPage;
import com.safesphere.backend.social.service.RepliesService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phase N5 (D19): Replies, its dot, and "I opened Replies" (API.md "Phase N5"). */
@RestController
@RequestMapping("/api/replies")
public class RepliesController {

    private final RepliesService replies;

    public RepliesController(RepliesService replies) {
        this.replies = replies;
    }

    @GetMapping
    public RepliesPage page(@AuthenticationPrincipal Jwt jwt) {
        return replies.page(CurrentUser.id(jwt));
    }

    @GetMapping("/marker")
    public Marker marker(@AuthenticationPrincipal Jwt jwt) {
        return new Marker(replies.fresh(CurrentUser.id(jwt)));
    }

    @PutMapping("/opened")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void opened(@AuthenticationPrincipal Jwt jwt) {
        replies.opened(CurrentUser.id(jwt));
    }
}
