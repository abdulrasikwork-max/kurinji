package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.PlaceDtos.PlaceResponse;
import com.safesphere.backend.social.dto.PostDtos.PostResponse;
import java.util.List;

/**
 * Phase 13C: Discover. No answer carries a count, a coordinate, or another person's timestamp.
 */
public final class DiscoverDtos {

    private DiscoverDtos() {
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
