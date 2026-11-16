package com.safesphere.backend.community.dto;

import java.time.Instant;
import java.util.List;

/**
 * Phase N3: request and answer shapes of the community endpoints. A roll number only ever arrives in a request (typed
 * by the applicant, or by a verifier / admin from the ID card); no answer carries one. toString() never prints it.
 */
public final class CommunityDtos {

    private CommunityDtos() {
    }

    // ---- the applicant ------------------------------------------------------------------------------------------

    /** POST /api/community/verification: one way only (IN_PERSON, the default, or ID_PHOTO). */
    public record StartRequest(String method, String rollNumber, String programme, Integer graduationYear) {
        @Override
        public String toString() {
            return "StartRequest[method=" + method + ", programme=" + programme + ", graduationYear=" + graduationYear
                    + ", rollNumber=<redacted>]";
        }
    }

    public record Programme(String code, String label, String level, int firstYear, int lastYear) {
    }

    public record CommunityView(String code, String name) {
    }

    public record MembershipView(String programme, String programmeLabel, int graduationYear, String method,
                                 Instant verifiedAt, Instant endsAt, Instant endedAt, String endReason) {
    }

    /**
     * An open request. [code] is filled ONLY in the answer that made it (start, new code); only its HMAC is stored, so
     * later answers say only whether a code is alive.
     */
    public record RequestView(String method, String programme, String programmeLabel, int graduationYear, String code,
                              boolean codeAlive, long codeExpiresInSeconds, boolean photoUploaded, Instant expiresAt) {
    }

    /** The latest refusal, ban or end, shown to the person concerned (no shadow decisions). */
    public record Outcome(String kind, String reason, Instant at) {
    }

    /** N-24 + owner's item 8: a verifier or admin corrected the spelling of the name; [from] = the earlier spelling. */
    public record NameCorrection(String from, Instant at) {
    }

    public record Staff(boolean verifier, boolean admin) {
    }

    /** GET /api/community/me. [state] NONE | PENDING | MEMBER | ENDED | BANNED. */
    public record MeView(String state, CommunityView community, MembershipView membership, RequestView request,
                         Outcome outcome, NameCorrection nameCorrection, boolean nameLocked, Staff staff) {
    }

    // ---- a verifier or an admin ---------------------------------------------------------------------------------

    /** POST /api/verifier/lookup: the code the applicant shows. */
    public record LookupRequest(String code) {
        @Override
        public String toString() {
            return "LookupRequest[code=<redacted>]";
        }
    }

    /** What the person deciding sees about a request (never the roll number: they type it from the ID card). */
    public record RequestDetail(String ref, String method, String name, String phoneHint, String programme,
                                String programmeLabel, int graduationYear, Instant createdAt, Instant photoUploadedAt) {
    }

    /**
     * POST .../requests/{ref}/decision. Approve: [rollNumber] typed from the ID card (must match the applicant's),
     * optional [name] = the spelling on the ID. Refuse: a [reason]; the roll number is not needed.
     */
    public record DecisionRequest(Boolean approve, String rollNumber, String name, String reason) {
        @Override
        public String toString() {
            return "DecisionRequest[approve=" + approve + ", reason=" + reason + ", rollNumber=<redacted>]";
        }
    }

    public record DecisionResult(String outcome, String reason) {
    }

    public record PhotoQueue(List<RequestDetail> requests) {
    }
}
