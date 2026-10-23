package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Phase 13B: post requests and answers. No answer carries a count of anything, a coordinate, or (for anyone but the
 * author) a timestamp: others get [postedPeriod] (a band) and the month the experience happened.
 */
public final class PostDtos {

    private PostDtos() {
    }

    /**
     * Body of POST /api/posts. Codes are the closed lists of PostEnums / ProfileLists; [experiencedMonth] is "YYYY-MM".
     * There is no default audience (D14): it must be chosen.
     */
    public record PostRequest(
            @Size(max = 20, message = "Unknown type") String type,
            @Size(max = 20000, message = "Text is too long") String body,
            @Size(max = 2000, message = "Tip is too long") String tip,
            @Size(max = 40, message = "Unknown category") String category,
            @Size(max = 40, message = "Unknown place") String placeId,
            @Size(max = 10, message = "Unknown precision") String precision,
            @Size(max = 40, message = "Unknown city") String city,
            @Size(max = 10, message = "Invalid month") String experiencedMonth,
            @Size(max = 12, message = "Unknown time of day") String daypart,
            @Size(max = 12, message = "Unknown audience") String audience,
            @Size(max = 12, message = "Unknown comment setting") String commentPolicy,
            @Size(max = 6, message = "Add at most 6 photos") List<@Size(max = 40) String> mediaIds) {
    }

    /**
     * Body of PUT /api/posts/{id}: the text, the "when", the category and the comment setting may change; audience and
     * precision may only stay or narrow. The place and the photos do not change. [city] is needed when narrowing to CITY.
     */
    public record PostEditRequest(
            @Size(max = 20000, message = "Text is too long") String body,
            @Size(max = 2000, message = "Tip is too long") String tip,
            @Size(max = 40, message = "Unknown category") String category,
            @Size(max = 10, message = "Unknown precision") String precision,
            @Size(max = 40, message = "Unknown city") String city,
            @Size(max = 10, message = "Invalid month") String experiencedMonth,
            @Size(max = 12, message = "Unknown time of day") String daypart,
            @Size(max = 12, message = "Unknown audience") String audience,
            @Size(max = 12, message = "Unknown comment setting") String commentPolicy) {
    }

    /** The place of an EXACT post: its name only (its cell is in GET /api/places/{id}). */
    public record PostPlace(String publicId, String name) {
    }

    public record MediaRef(String mediaId, int width, int height) {
    }

    /**
     * A post as the viewer may see it. [place] only for EXACT, [areaCell] (6 characters) only for AREA, [city] only for
     * CITY. [audience], [postedAt] and [visibleFrom] only for the author (null for everyone else); [postedPeriod] is the
     * band others see (THIS_WEEK / THIS_MONTH / EARLIER).
     */
    public record PostResponse(String publicId, String type, PersonSummary author, boolean own, String body, String tip,
                               String category, String precision, PostPlace place, String areaCell, String city,
                               String experiencedMonth, String daypart, String postedPeriod, boolean edited,
                               List<MediaRef> media, String audience, String commentPolicy, boolean canComment,
                               Instant postedAt, Instant visibleFrom) {
    }

    /** One page of a list, newest first; [nextCursor] is null on the last page (more only on an explicit request). */
    public record PostPage(List<PostResponse> items, String nextCursor) {
    }
}
