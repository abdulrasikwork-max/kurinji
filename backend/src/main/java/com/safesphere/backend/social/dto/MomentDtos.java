package com.safesphere.backend.social.dto;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Phase N4: moment requests and answers. No answer carries a count of anything, a location, or (for anyone but the
 * author) a timestamp of when a moment was shared: others get [When], a rough time. The one exact time everyone gets is
 * the time of a Who's in? plan, which the author wrote for them to read (N-50).
 */
public final class MomentDtos {

    private MomentDtos() {
    }

    /**
     * Body of POST /api/moments. [kind] SAY | WHOS_IN; [audience] COMMUNITY | MY_PEOPLE (no default: N-46); [text] up
     * to 500 characters; [planAt] (an ISO-8601 instant, within the next 24 hours) and [planWhere] (up to 60
     * characters) only on a WHOS_IN; [questionId] = today's daily question when this answers it. Sizes here only stop
     * oversized input; the exact rules are MomentPolicy's.
     */
    public record MomentRequest(
            @Size(max = 12, message = "Choose Say or Who's in?") String kind,
            @Size(max = 12, message = "Choose who can see this") String audience,
            @Size(max = 4000, message = "A moment has at most 500 characters") String text,
            @Size(max = 40, message = "Choose a time within the next 24 hours") String planAt,
            @Size(max = 400, message = "Where has at most 60 characters") String planWhere,
            @Size(max = 40, message = "That photo is not available") String mediaId,
            @Size(max = 40, message = "That question is not available") String questionId,
            @Size(max = 40, message = "That plan is not available") String happenedOf) {
    }

    /**
     * The author of a moment. [name] is the real name of the account (N-23). Sharing needs no profile (N-51), so
     * [profileId], [handle] and [photo] are null for an author who has not made one.
     */
    public record MomentAuthor(String profileId, String handle, String name, String photo) {
    }

    /**
     * The photo of a moment: its size in pixels, so the app can lay the card out before the picture arrives. The picture
     * itself is GET /api/moments/{id}/media (the moment's id; a photo has no address of its own).
     */
    public record Photo(int width, int height) {
    }

    /** The plan of a Who's in?: [at] exactly as the author set it (may be null), [where] in the author's words. */
    public record Plan(Instant at, String where) {
    }

    /** The daily question a moment answers, or the one pinned on Today. */
    public record QuestionRef(String id, String text) {
    }

    /**
     * When a moment was shared, roughly, in India time: [day] TODAY | YESTERDAY | THIS_WEEK | THIS_MONTH | EARLIER;
     * [part] MORNING | AFTERNOON | EVENING | NIGHT for today and yesterday, else null. Null while nobody else can see
     * the moment yet.
     */
    public record When(String day, String part) {
    }

    /**
     * A moment as the viewer may see it. [status]: LIVE (on Today now), ENDED (its 24 hours are over; others reach it
     * only while it is kept) or WAITING (nobody else can see it yet; only its author ever gets this). [kept] = the
     * author keeps it on their profile. [createdAt], [visibleAt] and [expiresAt] are the exact times and only the
     * author gets them (null for everyone else).
     */
    public record MomentResponse(String id, String kind, String audience, MomentAuthor author, boolean mine,
                                 String text, Photo photo, Plan plan, QuestionRef question, When when, String status, boolean kept,
                                 boolean collapsed, Notice notice, Boolean supportSuggested,
                                 Instant createdAt, Instant visibleAt, Instant expiresAt, Responses responses,
                                 HappenedRef happenedOf) {

        /** The author's own answer to sharing it, with one calm support line (never stored). */
        public MomentResponse withSupport() {
            return new MomentResponse(id, kind, audience, author, mine, text, photo, plan, question, when, status,
                    kept, collapsed, notice, Boolean.TRUE, createdAt, visibleAt, expiresAt, responses, happenedOf);
        }
    }

    /**
     * Phase N5: what a viewer may do and know about the responses to one moment. Every part is null when it does not apply.
     * [same] / [sayMore] on a Say; [more] = the author's follow-up; [plan] on a Who's in?; [canReply] = the viewer may
     * start a private reply; [replyId] = their private reply with the author, once it exists; [author] = the names only the
     * author gets. No number anywhere (N-88).
     */
    public record Responses(SameView same, SayMoreView sayMore, More more, PlanView plan, Boolean canReply,
                            String replyId, ForAuthor author) {
    }

    /** N-88: [mine] = the viewer said Same; [band] SOMEONE | A_FEW | MANY = the OTHER people who did (null: nobody). */
    public record SameView(boolean mine, String band) {
    }

    /** N-89: [mine] = the viewer asked the author to say more. */
    public record SayMoreView(boolean mine) {
    }

    /** N-89: the author's one follow-up: its words, a rough time for others, the exact time for the author only. */
    public record More(String text, When when, Instant at) {
    }

    /**
     * N-90: the viewer and a Who's in?. [you] AUTHOR | NONE | ASKED | IN | REMOVED; [open] = it takes new people now;
     * [calledOff]; [planId] = the plan's conversation, for its members once it has opened (B2).
     */
    public record PlanView(String you, boolean open, boolean calledOff, String planId) {
    }

    /**
     * The author's own lines (never sent to anyone else): up to three names each of who feels the same, who wants to
     * hear more, who asks to join (not set aside) and who is in; the "other" flags say more names are behind a tap.
     */
    public record ForAuthor(List<String> same, boolean otherSame, List<String> sayMore, boolean otherSayMore,
                            List<String> asking, boolean otherAsking, List<String> in, boolean otherIn) {
    }

    /** N-92: the Who's in? a "This happened" Say came from: its id and its words (at most 120 characters). */
    public record HappenedRef(String id, String text) {
    }

    /** GET /api/moments/{id}/people: the people of a moment. The author gets every list; a plan member only [in]. */
    public record People(List<Person> same, List<Person> sayMore, List<Person> asking, List<Person> setAside,
                         List<Person> in) {
    }

    /**
     * Someone in a list of a moment's people. [ref] = what the author acts on (an ask's id; null in other lists);
     * [name] the real name; [profileId] / [photo] when they have a profile; [programme] / [year] from the membership;
     * [myPeople] = a mutual connection of the viewer.
     */
    public record Person(String ref, String name, String profileId, String photo, String programme, Integer year,
                         boolean myPeople) {
    }

    /** Body of POST /api/moments/{id}/more: the follow-up, up to 500 characters. */
    public record MoreRequest(@Size(max = 4000, message = "A follow-up has at most 500 characters") String text) {
    }

    /**
     * What the author is told about their own moment; nobody else ever gets it. [state] COLLAPSED | HELD | REMOVED;
     * [reason] a code (NEW_MEMBER_PHOTO, AWAITING_CHECK, NEEDS_REVIEW or a guideline); [by] SYSTEM (a rule) or MODERATOR
     * (a person); [reviewable] = POST /api/content-checks/review applies; [decisionId] = a moderator's decision,
     * listed by GET /api/social-standing, where it can be appealed.
     */
    public record Notice(String state, String reason, String by, boolean reviewable, String decisionId) {
    }

    /** GET /api/profiles/{id}/moments: the person's moments the viewer may see: [live] now, and the ones they [kept]. */
    public record ProfileMoments(List<MomentResponse> live, List<MomentResponse> kept) {
    }

    /**
     * GET /api/moments/today: [question] = today's daily question (null when there is none), [moments] newest first,
     * [nextCursor] null on the last page (the wall ends: the app shows its end card).
     */
    public record TodayResponse(QuestionRef question, List<MomentResponse> moments, String nextCursor) {
    }

    /**
     * GET /api/moments/mine: [current] = the caller's moments that have not ended (waiting or live), newest first;
     * [kept] = the ones they keep on their profile (at most [keptMax]), most recently kept first.
     */
    public record MineResponse(List<MomentResponse> current, List<MomentResponse> kept, int keptMax) {
    }

    /** Body of POST /api/admin/questions: [day] = the India calendar day (YYYY-MM-DD), [text] up to 140 characters. */
    public record QuestionRequest(
            @Size(max = 10, message = "Give the day as YYYY-MM-DD") String day,
            @Size(max = 1000, message = "A question has at most 140 characters") String text) {
    }

    /** A daily question as admins see it in their list. */
    public record QuestionAdminView(String id, String day, String text, Instant createdAt) {
    }
}
