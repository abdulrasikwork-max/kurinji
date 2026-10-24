package com.safesphere.backend.moderation.controller;

import com.safesphere.backend.moderation.dto.ContentModerationDtos.AppealAccepted;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.MyReport;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ReportReceived;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ReportRequest;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.StandingView;
import com.safesphere.backend.moderation.service.ContentModerationService;
import com.safesphere.backend.moderation.service.ReportService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 13D: what every member can do about moderation: report, see their own reports, see decisions about themselves
 * and ask for a review. Outside /api/moderation/** (that path is for moderators only, SecurityConfig).
 */
@RestController
public class ReportController {

    private final ReportService reports;
    private final ContentModerationService moderation;

    public ReportController(ReportService reports, ContentModerationService moderation) {
        this.reports = reports;
        this.moderation = moderation;
    }

    /** 202 with the same answer whether new or already reported (M4: nothing to learn from it). */
    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportReceived report(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReportRequest request) {
        return reports.report(CurrentUser.id(jwt), request);
    }

    @GetMapping("/api/reports/mine")
    public List<MyReport> mine(@AuthenticationPrincipal Jwt jwt) {
        return reports.mine(CurrentUser.id(jwt));
    }

    @GetMapping("/api/social-standing")
    public StandingView standing(@AuthenticationPrincipal Jwt jwt) {
        return moderation.standing(CurrentUser.id(jwt));
    }

    @PostMapping("/api/social-standing/decisions/{decisionId}/appeal")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AppealAccepted appeal(@AuthenticationPrincipal Jwt jwt, @PathVariable String decisionId) {
        return moderation.appeal(CurrentUser.id(jwt), decisionId);
    }
}
