package com.safesphere.backend.community.dto;

import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.FlagReason;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Freshness;
import com.safesphere.backend.community.service.SignalPolicy.Precision;
import com.safesphere.backend.community.service.SignalPolicy.Reason;
import com.safesphere.backend.community.service.SignalPolicy.Support;
import com.safesphere.backend.intelligence.dto.IntelligenceDtos.AreaPicture;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * Phase 6 request and response bodies. Responses are explicit FIELD ALLOW-LISTS (same idea as the Emergency Context
 * Packet): nothing here can carry a user id, a name, an exact coordinate, a count or a timestamp of anyone else.
 */
public final class CommunityDtos {

    private CommunityDtos() {
    }

    private static final String BLOCK_CELL = "^[0-9b-hjkmnp-z]{7}$";

    /** A new report. [cell] is the ~150 m geohash computed ON THE PHONE; the exact location never leaves it. */
    public record ReportRequest(
            @NotNull(message = "Category is required") SignalCategory category,
            @NotNull(message = "Cell is required")
            @Pattern(regexp = BLOCK_CELL, message = "Cell must be a 7-character geohash") String cell) {
    }

    /** "I saw this too" or "no longer true". [near] = the caller's own current ~150 m cell (checked, never stored). */
    public record ContributionRequest(
            @NotNull(message = "Kind is required") ContributionKind kind,
            @NotNull(message = "Your current cell is required")
            @Pattern(regexp = BLOCK_CELL, message = "Cell must be a 7-character geohash") String near) {
    }

    /**
     * The same answer whether the report created a new signal or merged into an existing one, so the reporter
     * cannot learn that someone else reported the same place minutes ago (before it is public).
     */
    public record Accepted(String status, int appearsWithinMinutes) {
    }

    /**
     * One signal as anyone may see it. [cell] has the precision stated (6 = area, 7 = block). Phase 7 adds
     * [confidence] + [reasons] (aggregate, plain-language "why", nothing about any person) and [youFlagged] (only the
     * caller's own flag).
     */
    public record PublicSignal(String id, SignalCategory category, SignalGroup group, Precision precision, String cell,
                               double latitude, double longitude, Support support, Freshness freshness,
                               boolean disputed, ContributionKind yourContribution, Confidence confidence,
                               List<Reason> reasons, boolean youFlagged) {
    }

    /** Phase 7: "something is wrong with this signal". [near] = the caller's own current cell (checked, never stored). */
    public record FlagRequest(
            @NotNull(message = "Reason is required") FlagReason reason,
            @NotNull(message = "Your current cell is required")
            @Pattern(regexp = BLOCK_CELL, message = "Cell must be a 7-character geohash") String near) {
    }

    /** The same answer whether or not the flag changed anything (no oracle for how others flagged). */
    public record FlagAccepted(String status) {
    }

    /**
     * Phase 8 adds [picture] (Safety Intelligence for the same neighbourhood, computed from the same visible signals;
     * null if it could not be computed: the phone then simply shows no picture).
     */
    public record AreaResponse(String area, List<PublicSignal> signals, AreaPicture picture) {
    }

    /** One of the caller's own contributions (OWNER projection). No times: only what the person needs to manage it. */
    public record MyContribution(String signalId, SignalCategory category, ContributionKind kind, String cell,
                                 MyStatus status) {
    }

    public enum MyStatus {
        /** Recorded; not counted yet (publication delay or new-account probation). */
        PENDING,
        /** Counted and the signal is visible. */
        LIVE,
        /** Counted, but the signal is not visible (for example others said it is no longer true). */
        NOT_SHOWN,
        /** Phase 7: hidden while moderators review it (only the people who contributed see this). */
        UNDER_REVIEW
    }
}
