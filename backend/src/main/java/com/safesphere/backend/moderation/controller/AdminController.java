package com.safesphere.backend.moderation.controller;

import com.safesphere.backend.moderation.dto.ModerationDtos.AuditEntry;
import com.safesphere.backend.moderation.dto.ModerationDtos.RoleChange;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7 administration: appointing moderators and reading the moderation audit log. ADMIN only (SecurityConfig
 * and ModerationService both check, from the database). There is deliberately no endpoint that creates an ADMIN.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ModerationService service;

    public AdminController(ModerationService service) {
        this.service = service;
    }

    @PutMapping("/roles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RoleChange request) {
        service.changeRole(CurrentUser.id(jwt), request);
    }

    @GetMapping("/moderation-log")
    public List<AuditEntry> log(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "page", defaultValue = "0") int page) {
        return service.auditLog(CurrentUser.id(jwt), page);
    }

}
