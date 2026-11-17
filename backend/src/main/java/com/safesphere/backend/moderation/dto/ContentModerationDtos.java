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
     * a person); [youTookPart] = the caller wrote it, is the person, or reported it (another moderator must decide). Phase N4
     * (N-69): [priority] is QueuePolicy.priority (three tiers); [waitingHours] = whole hours since the case opened.
     */
    public record CaseSummary(String caseId, String targetKind, String status, String outcome, LocalDate openedOn,
                              String topReason, Map<String, Integer> reasons, int counting, int independent,
                              String contentState, int priority, boolean youTookPart, String openedBy,
                              String contentSource, int waitingHours) {
    }

    /**
     * Phase 13E: what the automatic check found (the moderator's "why"). [evidence] in the order found, each with the
     * flagged phrase as code-point offsets of the text ([start] -1 when unknown); no self-harm signal ever (not stored).
     */
    public record AssessmentView(String status, String action, String rule, String reason, List<EvidenceView> evidence,
                                 boolean strongLanguage, String normaliserVersion, String lexiconVersion,
                                 String modelVersion, String policyVersion) {
    }

    public record EvidenceView(String category, int severity, boolean targeted, boolean quoted, int start, int end) {
    }

    /** Phase 13E: POST /api/content-checks/review (the author's own content collapsed or held by the check). */
    public record ReviewRequest(@NotNull(message = "Choose what to review") @Size(max = 10) String targetKind,
                                @NotNull(message = "Choose what to review") @Size(max = 40) String targetId) {
    }


    /** The public profile of a PERSON case (or the author's handle is NOT shown for content cases). */
    public record PersonView(String displayName, String handle, String bio, String programme,
                             Integer graduationYear, boolean hasPhoto) {
    }

    /** The size of a moment photo (the picture itself: GET /api/moderation/content-cases/{id}/media, after a tap). */
    public record PhotoView(int width, int height) {
    }

    /**
     * What the case is about. MOMENT: the moment as written (also a My people one: needed to judge): [kind] SAY |
     * WHOS_IN, [audience], [body], the plan of a Who is in ([planAt], [planWhere]), the daily [question] it answers,
     * [photo]. PERSON: [person], the profile every member can see.
     */
    public record ContentView(String kind, String audience, String body, Instant planAt, String planWhere,
                              String question, PhotoView photo, PersonView person) {
    }

    /** One person in a case, pseudonymous. [role] AUTHOR / SUBJECT / REPORTER; [reason], [note], [counting] reporters. */
    public record Participant(String ref, String role, String reason, String note, boolean counting,
                              AccountAge accountAge, String linkGroup) {
    }

    /**
     * The author or person concerned: [strikePoints] (decayed, one decimal), [suggestedStep] (StrikePolicy), the pause
     * in force (if any). Phase N4 (N-56): [newMemberUntil] = the end of their 7-day new-member period, null once
     * established.
     */
    public record AuthorView(String ref, double strikePoints, String suggestedStep, String restriction,
                             Instant restrictedUntil, Instant newMemberUntil) {
    }

    /** One decision already taken on this case (oldest first). */
    public record CaseDecision(String action, String reason, LocalDate decidedOn, boolean byYou) {
    }

    public record CaseDetail(CaseSummary summary, ContentView content,
                             List<Participant> participants, AuthorView author, List<String> possibleActions,
                             List<CaseDecision> decisions, AssessmentView assessment) {
    }

    public record ActionRequest(@NotNull(message = "Action is required") ActionType action,
                                @NotNull(message = "Reason is required") ActionReason reason) {
    }

    /** An appeal about a social decision, as a moderator sees it: what was decided, never who appealed. */
    public record AppealView(String appealId, String status, LocalDate createdOn, String action, String reason,
                             String targetKind, String caseId, boolean youDecided) {
    }

}
