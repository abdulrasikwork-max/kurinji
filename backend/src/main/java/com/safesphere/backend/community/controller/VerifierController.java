package com.safesphere.backend.community.controller;

import com.safesphere.backend.community.dto.CommunityDtos.DecisionRequest;
import com.safesphere.backend.community.dto.CommunityDtos.DecisionResult;
import com.safesphere.backend.community.dto.CommunityDtos.LookupRequest;
import com.safesphere.backend.community.dto.CommunityDtos.RequestDetail;
import com.safesphere.backend.community.service.DecisionService;
import com.safesphere.backend.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase N3: in-person verification. Open to an active verifier or an admin, read from the database on every request
 * (SecurityConfig + RoleAuthorization.verifier(); DecisionService checks again). There is no list of requests here: a
 * verifier only ever sees the request whose code the applicant shows them.
 */
@RestController
@RequestMapping("/api/verifier")
public class VerifierController {

    private final DecisionService decisions;

    public VerifierController(DecisionService decisions) {
        this.decisions = decisions;
    }

    @PostMapping("/lookup")
    public RequestDetail lookup(@AuthenticationPrincipal Jwt jwt, @RequestBody LookupRequest request) {
        return decisions.lookup(CurrentUser.id(jwt), request);
    }

    @PostMapping("/requests/{ref}/decision")
    public DecisionResult decide(@AuthenticationPrincipal Jwt jwt, @PathVariable String ref,
                                 @RequestBody DecisionRequest request) {
        return decisions.decideInPerson(CurrentUser.id(jwt), ref, request);
    }
}
