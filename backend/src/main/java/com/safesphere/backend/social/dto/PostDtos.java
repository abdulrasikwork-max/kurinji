package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import com.safesphere.backend.social.policy.ButterflyTally;
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
            @Size(max = 6, message = "Add at most 6 photos") List<@Size(max = 40) String> mediaIds,
            Boolean approveFirst) {
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
            @Size(max = 12, message = "Unknown comment setting") String commentPolicy,
            Boolean approveFirst) {
    }

    /**
     * Phase 13D: what a moderator decided about the viewer's OWN post or perspective (never shown to anyone else):
     * [state] COLLAPSED / HELD / REMOVED, [reason] a code (null for a collapse by independent reports while it waits for
     * a moderator), [decisionId] the decision to ask a review of (null when no moderator has decided yet).
     */
    public record ModerationView(String state, String reason, String decisionId, String source, List<Span> flagged) {
    }

    /**
     * Phase 13E: a phrase the automatic check flagged in the author's OWN text, as code-point offsets [start, end) of the
     * text it was given (the body, then a new line and the tip; or the perspective). Only ever sent to the author.
     */
    public record Span(int start, int end) {
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
     * Phase 13C: [yourButterfly] is the viewer's OWN Cocoon state for this post (null / WANT / BEEN), never anyone
     * else's; [butterflies] only for the author of an Experience shared with others (C2: a number from 3 people up),
     * null for everyone else.
     * Phase 13D: [approveFirst] = perspectives from people not connected with the author wait for approval (Q6);
     * [moderation] only for the author (null when nothing happened); [collapsed] for everyone else: null, UNDER_REVIEW
     * (collapsed while a moderator looks at it) or BY_MODERATOR (a moderator decided to keep it collapsed). Nothing
     * here says that anyone reported anything.
     * Phase 13E: [collapsed] may also be BY_SYSTEM (the automatic check thinks it may be hurtful); the author's
     * [moderation] gains [source] (REPORTS / SYSTEM / MODERATOR) and, for SYSTEM, the flagged phrases;
     * [strongLanguage] (everyone) = the check found strong language (a viewer may choose to collapse it on their phone);
     * [supportSuggested] only in the author's own create / edit answer, never stored: the words suggest they may be
     * going through something hard, so the app shows a support line.
     */
    public record PostResponse(String publicId, String type, PersonSummary author, boolean own, String body, String tip,
                               String category, String precision, PostPlace place, String areaCell, String city,
                               String experiencedMonth, String daypart, String postedPeriod, boolean edited,
                               List<MediaRef> media, String audience, String commentPolicy, boolean canComment,
                               Instant postedAt, Instant visibleFrom, String yourButterfly,
                               ButterflyTally butterflies, boolean approveFirst, ModerationView moderation,
                               String collapsed, boolean strongLanguage, Boolean supportSuggested) {

        /** The same answer with the one-time support flag (the author's own write answer). */
        public PostResponse withSupport(boolean support) {
            return new PostResponse(publicId, type, author, own, body, tip, category, precision, place, areaCell, city,
                    experiencedMonth, daypart, postedPeriod, edited, media, audience, commentPolicy, canComment,
                    postedAt, visibleFrom, yourButterfly, butterflies, approveFirst, moderation, collapsed,
                    strongLanguage, support ? Boolean.TRUE : null);
        }
    }

    /** One page of a list, newest first; [nextCursor] is null on the last page (more only on an explicit request). */
    public record PostPage(List<PostResponse> items, String nextCursor) {
    }
}
