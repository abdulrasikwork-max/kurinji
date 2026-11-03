package com.safesphere.backend.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Phase 17G-1: Explore requests and answers. A request is a STRUCTURED query built from closed lists (a city, up to two
 * categories, up to four tags, an optional time of day); the words a person typed are understood on their phone and never
 * reach the server. An answer lists PLACES with what matched. No answer carries a coordinate, a time, or a count of posts,
 * people, reactions or ratings: [requested] and [matched] count only what the person asked for.
 */
public final class ExploreDtos {

    private ExploreDtos() {
    }

    /**
     * Body of POST /api/explore/search. [city] is a catalog city code; [categories] are the ten category codes (at most
     * two: a place has one category, so they are alternatives); [facets] the tag codes; [daypart] MORNING / AFTERNOON /
     * EVENING / NIGHT. At least one of categories, facets, daypart is needed.
     */
    public record ExploreRequest(
            @NotNull(message = "Choose a city from the list") @Size(max = 20, message = "Choose a city from the list") String city,
            @Size(max = 4, message = "Choose at most 2 categories") List<@Size(max = 40) String> categories,
            @Size(max = 8, message = "Choose at most 4 tags") List<@Size(max = 30) String> facets,
            @Size(max = 12, message = "Choose a time of day from the list") String daypart) {
    }

    /** A place an experience was shared about: its name, category and city (its cell is in GET /api/places/{id}). */
    public record ExplorePlace(String publicId, String name, String category, String city) {
    }

    /**
     * One place found. [matchedCategory] / [matchedFacets] / [matchedDaypart] say what matched; [matched] of [requested]
     * is "Matches 3 of the 4 things you asked for". Nothing says how many experiences or people stand behind it.
     */
    public record ExploreHit(ExplorePlace place, boolean matchedCategory, List<String> matchedFacets,
                             boolean matchedDaypart, int requested, int matched) {
    }

    /** At most 20 places, best match first; the list always ends. */
    public record ExploreResponse(String city, int requested, List<ExploreHit> hits) {
    }
}
