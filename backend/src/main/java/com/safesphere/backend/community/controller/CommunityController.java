package com.safesphere.backend.community.controller;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.community.dto.CommunityDtos.Accepted;
import com.safesphere.backend.community.dto.CommunityDtos.AreaResponse;
import com.safesphere.backend.community.dto.CommunityDtos.ContributionRequest;
import com.safesphere.backend.community.dto.CommunityDtos.FlagAccepted;
import com.safesphere.backend.community.dto.CommunityDtos.FlagRequest;
import com.safesphere.backend.community.dto.CommunityDtos.MyContribution;
import com.safesphere.backend.community.dto.CommunityDtos.ReportRequest;
import com.safesphere.backend.community.service.CommunityService;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.moderation.dto.ModerationDtos.AppealAccepted;
import com.safesphere.backend.moderation.dto.ModerationDtos.MyStanding;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 6: Community Safety Intelligence (place signals). JWT required for everything; the caller is the JWT subject.
 * Nothing here accepts a user id, and nothing returns one.
 */
@RestController
@RequestMapping("/api/community")
public class CommunityController {

    private final CommunityService service;
    private final ModerationService moderation;

    public CommunityController(CommunityService service, ModerationService moderation) {
        this.service = service;
        this.moderation = moderation;
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted report(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReportRequest request) {
        return service.report(CurrentUser.id(jwt), request.category(), request.cell());
    }

    @PostMapping("/signals/{id}/contributions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted contribute(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                               @Valid @RequestBody ContributionRequest request) {
        return service.contribute(CurrentUser.id(jwt), id, request.kind(), request.near());
    }

    /** [cell] = the caller's own ~1 km area cell (6-character geohash), computed on the phone. */
    @GetMapping("/area")
    public AreaResponse area(@AuthenticationPrincipal Jwt jwt, @RequestParam("cell") String cell) {
        if (!GeoCell.isValid(cell, GeoCell.AREA_PRECISION)) {
            throw new InvalidRequestException("Cell must be a 6-character geohash");
        }
        return service.area(CurrentUser.id(jwt), cell);
    }

    @GetMapping("/mine")
    public List<MyContribution> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(CurrentUser.id(jwt));
    }

    @DeleteMapping("/mine/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        service.withdraw(CurrentUser.id(jwt), id);
    }

    // ---- Phase 7: flags, the person's own standing, appeals ----------------------------------------------------------

    @PostMapping("/signals/{id}/flags")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public FlagAccepted flag(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                             @Valid @RequestBody FlagRequest request) {
        return service.flag(CurrentUser.id(jwt), id, request.reason(), request.near());
    }

    /** Only the caller's own state: whether a moderator paused their contributions, and their appeal. */
    @GetMapping("/standing")
    public MyStanding standing(@AuthenticationPrincipal Jwt jwt) {
        return moderation.myStanding(CurrentUser.id(jwt));
    }

    /** Asks for a different moderator to review the current restriction. */
    @PostMapping("/appeals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AppealAccepted appeal(@AuthenticationPrincipal Jwt jwt) {
        return moderation.appeal(CurrentUser.id(jwt));
    }
}
