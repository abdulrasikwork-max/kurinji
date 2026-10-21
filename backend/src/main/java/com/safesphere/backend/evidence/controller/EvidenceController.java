package com.safesphere.backend.evidence.controller;

import com.safesphere.backend.evidence.dto.EvidenceDtos.CustodyReport;
import com.safesphere.backend.evidence.dto.EvidenceDtos.DeclareRequest;
import com.safesphere.backend.evidence.dto.EvidenceDtos.DeleteRequest;
import com.safesphere.backend.evidence.dto.EvidenceDtos.EvidenceResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.VaultKeyResponse;
import com.safesphere.backend.evidence.dto.EvidenceDtos.VerifyResponse;
import com.safesphere.backend.evidence.service.EvidenceService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Evidence Vault endpoints (Phase 5). JWT required; owner only (another person's item or emergency answers 404).
 *
 *   GET  /api/evidence/vault-key                    public half of the offline vault recovery key (or "not available")
 *   POST /api/emergencies/{id}/evidence             declare an item (201 new, 200 already known)
 *   GET  /api/emergencies/{id}/evidence             the emergency's items
 *   GET  /api/emergencies/{id}/evidence/custody     chain of custody + verification
 *   GET  /api/evidence/{id}                         one item (receivedBytes tells where to resume)
 *   PUT  /api/evidence/{id}/content?offset=N        one piece of ciphertext (application/octet-stream)
 *   POST /api/evidence/{id}/seal                    verify size + SHA-256, then SEALED
 *   POST /api/evidence/{id}/verify                  re-hash the STORED copy now (VERIFIED, or reopened if damaged)
 *   GET  /api/evidence/{id}/content                 download the sealed ciphertext
 *   POST /api/evidence/{id}/delete                  {password}; refused while ACTIVE and for 24 h after; 429 lockout
 */
@RestController
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    @GetMapping("/api/evidence/vault-key")
    public VaultKeyResponse vaultKey() {
        return service.vaultKey();
    }

    @PostMapping("/api/emergencies/{id}/evidence")
    public ResponseEntity<EvidenceResponse> declare(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                                    @Valid @RequestBody DeclareRequest request) {
        EvidenceService.Declared declared = service.declare(CurrentUser.id(jwt), id, request);
        return ResponseEntity.status(declared.created() ? HttpStatus.CREATED : HttpStatus.OK).body(declared.item());
    }

    @GetMapping("/api/emergencies/{id}/evidence")
    public List<EvidenceResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.list(CurrentUser.id(jwt), id);
    }

    @GetMapping("/api/emergencies/{id}/evidence/custody")
    public CustodyReport custody(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.custody(CurrentUser.id(jwt), id);
    }

    @GetMapping("/api/evidence/{id}")
    public EvidenceResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.get(CurrentUser.id(jwt), id);
    }

    /** The body is read as a raw stream (never buffered as a whole) and limited to the configured piece size. */
    @PutMapping(value = "/api/evidence/{id}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public EvidenceResponse upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                   @RequestParam("offset") long offset, HttpServletRequest request) throws IOException {
        return service.upload(CurrentUser.id(jwt), id, offset, request.getContentLengthLong(), request.getInputStream());
    }

    @PostMapping("/api/evidence/{id}/seal")
    public EvidenceResponse seal(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.seal(CurrentUser.id(jwt), id);
    }

    @PostMapping("/api/evidence/{id}/verify")
    public VerifyResponse verify(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.verify(CurrentUser.id(jwt), id);
    }

    @GetMapping(value = "/api/evidence/{id}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        EvidenceService.Download download = service.download(CurrentUser.id(jwt), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header("X-Evidence-Sha256", download.ciphertextSha256())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evidence-" + id + ".ssev\"")
                .body(new FileSystemResource(download.file()));
    }

    @PostMapping("/api/evidence/{id}/delete")
    public EvidenceResponse delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                   @Valid @RequestBody DeleteRequest request) {
        return service.delete(CurrentUser.id(jwt), id, request.password());
    }
}
