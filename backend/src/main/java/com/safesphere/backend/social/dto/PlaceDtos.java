package com.safesphere.backend.social.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Phase 13B: place requests and answers. A place carries no creator and no time; its cell is the ~150 m geohash the
 * phone chose (a public point, needed by the phone to keep places near its private places out of posts).
 */
public final class PlaceDtos {

    private PlaceDtos() {
    }

    /** Body of POST /api/places. Exact rules in PlaceNamePolicy / PlaceService. */
    public record PlaceRequest(
            @NotNull(message = "Name is required") @Size(max = 200, message = "Name is too long") String name,
            @NotNull(message = "Category is required") @Size(max = 40, message = "Unknown category") String category,
            @NotNull(message = "Cell is required") @Size(max = 12, message = "Invalid cell") String cell) {
    }

    /** Phase 17G-2 (U5): body of PUT /api/places/{id}/rating. Exact rule in RatingPolicy (1 to 5). */
    public record RatingRequest(
            @NotNull(message = "Choose 1 to 5") @Min(value = 1, message = "Choose 1 to 5")
            @Max(value = 5, message = "Choose 1 to 5") Integer rating) {
    }

    /** Phase 17G-2 (U5): the caller's OWN visit: [rating] is their own rating or null (went, not rated). Never anyone else's. */
    public record VisitResponse(Integer rating) {
    }

    /** [city]: Phase 13C, the launch city the server placed it in (CityAreas), or null. */
    public record PlaceResponse(String publicId, String name, String category, String cell, String areaCell,
                                String city) {
    }
}
