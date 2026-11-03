package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.PlaceDtos.PlaceRequest;
import com.safesphere.backend.social.dto.PlaceDtos.PlaceResponse;
import com.safesphere.backend.social.dto.PlaceDtos.RatingRequest;
import com.safesphere.backend.social.dto.PlaceDtos.VisitResponse;
import com.safesphere.backend.social.service.PlaceService;
import com.safesphere.backend.social.service.PlaceVisitService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Phase 13B: places. Cells only (7 characters to create, 6 to list nearby); never coordinates. */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceService places;
    private final PlaceVisitService visits;

    public PlaceController(PlaceService places, PlaceVisitService visits) {
        this.places = places;
        this.visits = visits;
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

    /** Phase 13C: places by name (no people, no post text); optionally in one launch city. */
    @GetMapping("/search")
    public List<PlaceResponse> search(@AuthenticationPrincipal Jwt jwt, @RequestParam(value = "q", required = false) String q,
                                      @RequestParam(value = "city", required = false) String city) {
        return places.search(CurrentUser.id(jwt), q, city);
    }

    /** Phase 17G-2 (U5): "I went" - the caller's private record (a place they can see; idempotent). */
    @PutMapping("/{publicId}/went")
    public VisitResponse went(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return visits.went(CurrentUser.id(jwt), publicId);
    }

    /** Takes "I went" back, and the rating with it. */
    @DeleteMapping("/{publicId}/went")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notWent(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        visits.notWent(CurrentUser.id(jwt), publicId);
    }

    /** The caller's rating, 1 to 5, for a place they went to (409 before "I went"). */
    @PutMapping("/{publicId}/rating")
    public VisitResponse rate(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                              @Valid @RequestBody RatingRequest request) {
        return visits.rate(CurrentUser.id(jwt), publicId, request.rating());
    }

    /** Takes the rating back and keeps "I went". */
    @DeleteMapping("/{publicId}/rating")
    public VisitResponse clearRating(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return visits.clearRating(CurrentUser.id(jwt), publicId);
    }

    @GetMapping("/{publicId}")
    public PlaceResponse one(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return places.one(CurrentUser.id(jwt), publicId);
    }
}
