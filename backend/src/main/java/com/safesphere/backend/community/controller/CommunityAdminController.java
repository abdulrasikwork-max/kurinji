package com.safesphere.backend.community.controller;

import com.safesphere.backend.community.dto.CommunityDtos.DecisionRequest;
import com.safesphere.backend.community.dto.CommunityDtos.DecisionResult;
import com.safesphere.backend.community.dto.CommunityDtos.LogEntry;
import com.safesphere.backend.community.dto.CommunityDtos.NameChange;
import com.safesphere.backend.community.dto.CommunityDtos.RequestDetail;
import com.safesphere.backend.community.dto.CommunityDtos.VerifierChange;
import com.safesphere.backend.community.dto.CommunityDtos.VerifierView;
import com.safesphere.backend.community.service.CommunityAdminService;
import com.safesphere.backend.community.service.DecisionService;
import com.safesphere.backend.security.CurrentUser;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase N3: the community's admin endpoints (ADMIN only: SecurityConfig /api/admin/** + the services check again).
 */
@RestController
@RequestMapping("/api/admin/community")
public class CommunityAdminController {

    private final DecisionService decisions;
    private final CommunityAdminService admin;

    public CommunityAdminController(DecisionService decisions, CommunityAdminService admin) {
        this.decisions = decisions;
        this.admin = admin;
    }

    @GetMapping("/photo-requests")
    public List<RequestDetail> photoQueue(@AuthenticationPrincipal Jwt jwt) {
        return decisions.photoQueue(CurrentUser.id(jwt));
    }

    /** The ID photo: never stored by a cache, never sniffed, shown inline only. */
    @GetMapping("/requests/{ref}/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable String ref) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(decisions.photo(CurrentUser.id(jwt), ref));
    }

    @PostMapping("/requests/{ref}/decision")
    public DecisionResult decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String ref,
                                 @RequestBody DecisionRequest request) {
        return decisions.decideByPhoto(CurrentUser.id(jwt), ref, request);
    }

    @GetMapping("/verifiers")
    public List<VerifierView> verifiers(@AuthenticationPrincipal Jwt jwt) {
        return admin.verifiers(CurrentUser.id(jwt));
    }

    @PutMapping("/verifiers")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeVerifier(@AuthenticationPrincipal Jwt jwt, @RequestBody VerifierChange change) {
        admin.changeVerifier(CurrentUser.id(jwt), change);
    }

    @PutMapping("/members/name")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void correctName(@AuthenticationPrincipal Jwt jwt, @RequestBody NameChange change) {
        admin.correctName(CurrentUser.id(jwt), change);
    }

    @GetMapping("/log")
    public List<LogEntry> log(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(name = "page", defaultValue = "0") int page) {
        return admin.log(CurrentUser.id(jwt), page);
    }
}
