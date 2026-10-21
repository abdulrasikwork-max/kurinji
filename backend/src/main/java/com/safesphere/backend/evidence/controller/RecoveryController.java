package com.safesphere.backend.evidence.controller;

import com.safesphere.backend.evidence.dto.EvidenceDtos.AttachRecoveryKeyRequest;
import com.safesphere.backend.evidence.dto.EvidenceDtos.EvidenceResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoverableItemsPage;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoveryEnvelopeRequest;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoveryEnvelopeResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.RecoveryStatusResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.SetRecoveryRequest;
import com.safesphere.backend.evidence.service.RecoveryService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Restore evidence on a new phone (Phase 5, V11). JWT required; everything belongs to the caller.
 *
 *   GET  /api/evidence/recovery                 status only (configured, versions, when it was last fetched)
 *   PUT  /api/evidence/recovery                 {password, ...envelope}: set up or change the passphrase
 *   POST /api/evidence/recovery/envelope        {password}: the envelope, for a phone that knows the passphrase
 *   GET  /api/evidence/recovery/items?page=N    SEALED items with their recovery-wrapped key
 *   PUT  /api/evidence/{id}/recovery-key        attach an item's recovery copy (write-once per key version)
 */
@RestController
public class RecoveryController {

    private final RecoveryService service;

    public RecoveryController(RecoveryService service) {
        this.service = service;
    }

    @GetMapping("/api/evidence/recovery")
    public RecoveryStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return service.status(CurrentUser.id(jwt));
    }

    @PutMapping("/api/evidence/recovery")
    public RecoveryStatusResponse set(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SetRecoveryRequest request) {
        return service.set(CurrentUser.id(jwt), request);
    }

    @PostMapping("/api/evidence/recovery/envelope")
    public ResponseEntity<RecoveryEnvelopeResponse> envelope(@AuthenticationPrincipal Jwt jwt,
                                                             @Valid @RequestBody RecoveryEnvelopeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.envelope(CurrentUser.id(jwt), request.password()));
    }

    @GetMapping("/api/evidence/recovery/items")
    public ResponseEntity<RecoverableItemsPage> items(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(value = "page", defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.recoverable(CurrentUser.id(jwt), page));
    }

    @PutMapping("/api/evidence/{id}/recovery-key")
    public EvidenceResponse attach(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                   @Valid @RequestBody AttachRecoveryKeyRequest request) {
        return service.attach(CurrentUser.id(jwt), id, request);
    }
}
