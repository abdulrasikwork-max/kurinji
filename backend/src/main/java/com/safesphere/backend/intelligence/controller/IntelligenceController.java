package com.safesphere.backend.intelligence.controller;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.intelligence.dto.IntelligenceDtos.RegionPack;
import com.safesphere.backend.intelligence.service.RegionIntelligenceService;
import com.safesphere.backend.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 8 Safety Intelligence (read-only; JWT required; the caller is the JWT subject and only spends their own
 * budget). The neighbourhood picture is part of GET /api/community/area; this controller adds the region pack.
 */
@RestController
@RequestMapping("/api/intelligence")
public class IntelligenceController {

    private final RegionIntelligenceService regions;

    public IntelligenceController(RegionIntelligenceService regions) {
        this.regions = regions;
    }

    /** [cell] = one 5-character region (~4.9 km), computed on the phone. Never a route, start or destination. */
    @GetMapping("/region")
    public RegionPack region(@AuthenticationPrincipal Jwt jwt, @RequestParam("cell") String cell) {
        if (!GeoCell.isValid(cell, GeoCell.REGION_PRECISION)) {
            throw new InvalidRequestException("Cell must be a 5-character geohash");
        }
        return regions.region(CurrentUser.id(jwt), cell);
    }
}
