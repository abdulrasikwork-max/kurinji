package com.safesphere.backend.moderation.dto;

import com.safesphere.backend.moderation.dto.ModerationDtos.AccountAge;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionReason;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Phase 13D request and response bodies (docs/PHASE13D_PLAN.md §5). FIELD ALLOW-LISTS, as in Phase 7:
 *   - the reported person's answers never name a reporter, never say that a report exists, never carry a count;
 *   - a reporter sees only their own reports (kind, reason, status, outcome, a band of when);
 *   - moderators see the content and case pseudonyms (CaseRefs), never names, e-mails, ids or any safety data, except a
 *     PERSON case shows the person's public profile (what every signed-in member can see).
 */
public final class ContentModerationDtos {

    private ContentModerationDtos() {
    }

    // ---- everyone: reports ------------------------------------------------------------------------------------------

    /** POST /api/reports. [note] only (and always) with reason OTHER. */
    public record ReportRequest(
            @NotNull(message = "Choose what to report") @Size(max = 10, message = "Unknown kind") String targetKind,
            @NotNull(message = "Choose what to report") @Size(max = 40, message = "Not found") String targetId,
            @NotNull(message = "Choose a reason") @Size(max = 30, message = "Unknown reason") String reason,
            @Size(max = 1000, message = "A note has at most 200 characters") String note) {
    }

    /** Always the same answer, also when this person had already reported it. */
    public record ReportReceived(String status) {
    }

    /** One of the caller's own reports: RECEIVED until a moderator decided, then REVIEWED with the outcome. */
    public record MyReport(String reportId, String targetKind, String reason, String status, String outcome,
                           String period) {
    }

    // ---- the person concerned: their standing -----------------------------------------------------------------------

    /** The pause in force for the caller (null when none). [until] is the caller's own information. */
    public record RestrictionView(String level, Instant until, String reason, String decisionId, String appeal,
                                  boolean canAppeal) {
    }

    /**
     * A moderator's decision about the caller or their content (last 90 days; decisions that found no violation are
     * not listed: the person was never told that anything was reported). [targetId] = the post or perspective, while it
     * exists; [appeal] NONE / OPEN / UPHELD / OVERTURNED.
     */
    public record DecisionView(String decisionId, String action, String reason, String targetKind, String targetId,
                               String period, String appeal, boolean canAppeal) {
    }

    public record StandingView(RestrictionView restriction, List<DecisionView> decisions) {
    }

    public record AppealAccepted(String status) {
    }

    // ---- moderators ---------------------------------------------------------------------------------------------------

    /**
     * One case in the queue (highest priority first, then oldest). [reasons] counts open reports per reason;
     * [counting] / [independent] as ReportPolicy; [contentState] the post's or perspective's moderation state (null for
     * a person); [youTookPart] = the caller wrote it, is the person, or reported it (another moderator must decide).
     */
    public record CaseSummary(String caseId, String targetKind, String status, String outcome, LocalDate openedOn,
                              String topReason, Map<String, Integer> reasons, int counting, int independent,
                              String contentState, int priority, boolean youTookPart) {
    }

    public record MediaView(String mediaId, int width, int height) {
    }

    /** The public profile of a PERSON case (or the author's handle is NOT shown for content cases). */
    public record PersonView(String displayName, String handle, String bio, String city, List<String> interests) {
    }

    /**
     * What the case is about. POST: the post as written (also Connections-only text: needed to judge). COMMENT: the
     * perspective, with its post's text as context ([contextBody], at most 500 characters). PERSON: [person].
     * [placeName] only for EXACT posts, [areaCell] only for AREA posts (as others see it).
     */
    public record ContentView(String postType, String commentKind, String body, String tip, String category,
                              String precision, String placeName, String areaCell, String city, String audience,
                              String experiencedMonth, String daypart, List<MediaView> media, String contextBody,
                              PersonView person) {
    }

    /** The text before an edit made during this case (Q7). A day, not a time. */
    public record RevisionView(String body, String tip, LocalDate replacedOn) {
    }

    /** One person in a case, pseudonymous. [role] AUTHOR / SUBJECT / REPORTER; [reason], [note], [counting] reporters. */
    public record Participant(String ref, String role, String reason, String note, boolean counting,
                              AccountAge accountAge, String linkGroup) {
    }

    /**
     * The author or person concerned: [strikePoints] (decayed, one decimal), [suggestedStep] (StrikePolicy), the pause
     * in force (if any).
     */
    public record AuthorView(String ref, double strikePoints, String suggestedStep, String restriction,
                             Instant restrictedUntil) {
    }

    /** One decision already taken on this case (oldest first). */
    public record CaseDecision(String action, String reason, LocalDate decidedOn, boolean byYou) {
    }

    public record CaseDetail(CaseSummary summary, ContentView content, List<RevisionView> revisions,
                             List<Participant> participants, AuthorView author, List<String> possibleActions,
                             List<CaseDecision> decisions) {
    }

    public record ActionRequest(@NotNull(message = "Action is required") ActionType action,
                                @NotNull(message = "Reason is required") ActionReason reason) {
    }

    /** An appeal about a social decision, as a moderator sees it: what was decided, never who appealed. */
    public record AppealView(String appealId, String status, LocalDate createdOn, String action, String reason,
                             String targetKind, String caseId, boolean youDecided) {
    }

    public record MergeRequest(@NotNull(message = "Choose the place to keep") @Size(max = 40) String into) {
    }

    public record PlaceView(String placeId, String name, String category, String cell, String mergedInto) {
    }
}
