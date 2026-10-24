package com.safesphere.backend.moderation.controller;

import com.safesphere.backend.moderation.dto.ContentModerationDtos.ActionRequest;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AppealView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseDetail;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseSummary;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.MergeRequest;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.PlaceView;
import com.safesphere.backend.moderation.dto.ModerationDtos.AppealDecision;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.service.ContentModerationService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 13D moderator API for posts, perspectives, people and places. Under /api/moderation/**, so SecurityConfig lets
 * only MODERATOR / ADMIN accounts (read from the database on every request) through; the service checks again.
 */
@RestController
@RequestMapping("/api/moderation")
public class ContentModerationController {

    private final ContentModerationService service;

    public ContentModerationController(ContentModerationService service) {
        this.service = service;
    }

    @GetMapping("/content-cases")
    public List<CaseSummary> cases(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(name = "status", defaultValue = "OPEN") CaseStatus status) {
        return service.listCases(CurrentUser.id(jwt), status);
    }

    @GetMapping("/content-cases/{id}")
    public CaseDetail caseDetail(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service.caseDetail(CurrentUser.id(jwt), id);
    }

    @PostMapping("/content-cases/{id}/actions")
    public CaseDetail act(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                          @Valid @RequestBody ActionRequest request) {
        return service.act(CurrentUser.id(jwt), id, request.action(), request.reason());
    }

    /** A photo of the case's post: never cached anywhere (private, no-store). */
    @GetMapping("/content-cases/{id}/media/{mediaId}")
    public ResponseEntity<byte[]> media(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                        @PathVariable String mediaId) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(service.media(CurrentUser.id(jwt), id, mediaId));
    }

    @GetMapping("/content-appeals")
    public List<AppealView> appeals(@AuthenticationPrincipal Jwt jwt) {
        return service.listAppeals(CurrentUser.id(jwt));
    }

    @PostMapping("/content-appeals/{id}/decision")
    public AppealView decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                             @Valid @RequestBody AppealDecision request) {
        return service.decideAppeal(CurrentUser.id(jwt), id, request.decision());
    }

    @GetMapping("/places")
    public List<PlaceView> places(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "area") String area) {
        return service.places(CurrentUser.id(jwt), area);
    }

    @PostMapping("/places/{id}/merge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void merge(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                      @Valid @RequestBody MergeRequest request) {
        service.merge(CurrentUser.id(jwt), id, request.into());
    }
}
