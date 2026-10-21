package com.safesphere.backend.moderation.controller;

import com.safesphere.backend.moderation.dto.ModerationDtos.Access;
import com.safesphere.backend.moderation.dto.ModerationDtos.ActionRequest;
import com.safesphere.backend.moderation.dto.ModerationDtos.AppealDecision;
import com.safesphere.backend.moderation.dto.ModerationDtos.AppealView;
import com.safesphere.backend.moderation.dto.ModerationDtos.CaseDetail;
import com.safesphere.backend.moderation.dto.ModerationDtos.CaseSummary;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7 moderation API. SecurityConfig lets only MODERATOR / ADMIN accounts (read from the database on every
 * request) past /api/moderation/** except /access; ModerationService checks the role again (defence in depth).
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

    @GetMapping("/cases")
    public List<CaseSummary> cases(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(name = "status", defaultValue = "OPEN") CaseStatus status) {
        return service.listCases(CurrentUser.id(jwt), status);
    }

    @GetMapping("/cases/{id}")
    public CaseDetail caseDetail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.caseDetail(CurrentUser.id(jwt), id);
    }

    @PostMapping("/cases/{id}/actions")
    public CaseDetail act(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                          @Valid @RequestBody ActionRequest request) {
        return service.act(CurrentUser.id(jwt), id, request);
    }

    @GetMapping("/appeals")
    public List<AppealView> appeals(@AuthenticationPrincipal Jwt jwt) {
        return service.listAppeals(CurrentUser.id(jwt));
    }

    @PostMapping("/appeals/{id}/decision")
    public AppealView decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                             @Valid @RequestBody AppealDecision request) {
        return service.decideAppeal(CurrentUser.id(jwt), id, request.decision());
    }
}
