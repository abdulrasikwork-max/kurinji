package com.safesphere.backend.emergency.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of POST /api/emergencies/{id}/locations: 1 to 50 points, so a phone that was offline can upload
 * what it collected in one request. There is NO emergency or user id in the body: the emergency comes
 * from the URL and its owner is checked against the JWT.
 */
public record LocationBatchRequest(

        @NotNull(message = "Points are required")
        @Size(min = 1, max = 50, message = "Send between 1 and 50 points")
        List<@NotNull(message = "A point must not be empty") @Valid LocationPointRequest> points
) {
}
