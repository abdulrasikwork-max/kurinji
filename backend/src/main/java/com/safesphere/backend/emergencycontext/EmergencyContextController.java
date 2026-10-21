package com.safesphere.backend.emergencycontext;

import com.safesphere.backend.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/emergencies/{id}/context?audience=OWNER|SAFETY_CIRCLE (Phase 4D). JWT required, owner only: another
 * user's emergency answers 404. The SAFETY_CIRCLE projection is a preview of what contacts are told.
 */
@RestController
public class EmergencyContextController {

    private final EmergencyContextService service;

    public EmergencyContextController(EmergencyContextService service) {
        this.service = service;
    }

    @GetMapping("/api/emergencies/{id}/context")
    public Object context(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                          @RequestParam(name = "audience", required = false) String audience) {
        return service.packet(CurrentUser.id(jwt), id, EmergencyContextService.parseAudience(audience));
    }
}
