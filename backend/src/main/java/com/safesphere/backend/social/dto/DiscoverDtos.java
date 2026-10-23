package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.PlaceDtos.PlaceResponse;
import com.safesphere.backend.social.dto.PostDtos.PostResponse;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Phase 13C: Discover, the Cocoon and butterflies. No answer carries a count (the author's thresholded number lives in
 * PostResponse.butterflies), a coordinate, or another person's timestamp.
 */
public final class DiscoverDtos {

    private DiscoverDtos() {
    }

    /** Body of PUT /api/posts/{id}/butterfly; a missing state means WANT. */
    public record ButterflyRequest(@Size(max = 10, message = "Unknown state") String state) {
    }

    public record ButterflyResponse(String state) {
    }

    /** One Cocoon item: the post as the owner may see it now, its state, and when it was saved (a band). */
    public record CocoonItem(PostResponse post, String state, String savedPeriod) {
    }

    public record CocoonPage(List<CocoonItem> items, String nextCursor) {
    }

    /** A post picked for a list, with the reason codes that explain why ("Why am I seeing this?"). */
    public record DiscoverItem(PostResponse post, List<String> reasons) {
    }

    public record EditionSection(String kind, List<DiscoverItem> items) {
    }

    /**
     * The weekly Edition for one city. [weekOf] is the Monday of the Edition week (a public calendar date, not anyone's
     * time); [theme] the category of the week. Sections with nothing in them are left out; the list always ends.
     */
    public record Edition(String city, String weekOf, String theme, List<EditionSection> sections) {
    }

    public record DiscoverPage(List<DiscoverItem> items, String nextCursor) {
    }

    /** A place page: the place and its exact-place Everyone posts, newest first. */
    public record PlacePage(PlaceResponse place, List<PostResponse> items, String nextCursor) {
    }
}
