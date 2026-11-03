package com.safesphere.backend.explore;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Phase 17G-3: the optional understanding of a typed Explore request. The request is the typed words only (at most 200
 * characters, never stored, never logged); the answer is the SAME chips the phone's word list produces - closed-list codes,
 * nothing else. It carries no place, post, person, count or explanation.
 */
public final class UnderstandingDtos {

    private UnderstandingDtos() {
    }

    public record UnderstandRequest(
            @NotBlank(message = "Type what you are looking for")
            @Size(max = 200, message = "Please use at most 200 characters") String text) {
        /** Never the words (a request object must not leak them through a log line). */
        @Override
        public String toString() {
            return "UnderstandRequest[text=<" + (text == null ? 0 : text.length()) + " chars>]";
        }
    }

    /** [daypart] is null when none was understood. */
    public record UnderstandResponse(List<String> categories, List<String> facets, String daypart) {
    }

    /** Whether the switch should be offered at all on this server (it is not a promise that a call will succeed). */
    public record UnderstandingStatus(boolean available) {
    }
}
