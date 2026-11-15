package com.safesphere.backend.moderation.controller;

import com.safesphere.backend.moderation.dto.ModerationDtos.Access;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7 moderation API, reduced in N1 to the one call that is not about a content case (those are in
 * ContentModerationController). SecurityConfig lets only MODERATOR / ADMIN accounts (read from the database on every
 * request) past /api/moderation/** except /access.
 */
@RestController
@RequestMapping("/api/moderation")
public class ModerationController {

    private final ModerationService service;

    public ModerationController(ModerationService service) {
        this.service = service;
    }

    /** Open to every signed-in account: tells the app whether to show the Moderation entry at all. */
    @GetMapping("/access")
    public Access access(@AuthenticationPrincipal Jwt jwt) {
        return service.access(CurrentUser.id(jwt));
    }
}
