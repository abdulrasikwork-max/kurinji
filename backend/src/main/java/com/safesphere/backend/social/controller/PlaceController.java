package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.PlaceDtos.PlaceRequest;
import com.safesphere.backend.social.dto.PlaceDtos.PlaceResponse;
import com.safesphere.backend.social.service.PlaceService;
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

/** Phase 13B: places. Cells only (7 characters to create, 6 to list nearby); never coordinates. */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService places;

    public PlaceController(PlaceService places) {
        this.places = places;
    }

    /** 200 with the place: a new one, or the existing place with the same name in that cell. */
    @PostMapping
    public PlaceResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PlaceRequest request) {
        return places.create(CurrentUser.id(jwt), request);
    }

    @GetMapping("/nearby")
    public List<PlaceResponse> nearby(@AuthenticationPrincipal Jwt jwt, @RequestParam("area") String area) {
        return places.nearby(CurrentUser.id(jwt), area);
    }

    @GetMapping("/{publicId}")
    public PlaceResponse one(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return places.one(CurrentUser.id(jwt), publicId);
    }
}
