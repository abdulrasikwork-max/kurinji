package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Phase N5 (docs/PHASE_N5.md, N-88 to N-94): the locked rules of responses and plans, as constants and pure functions.
 * Pure: the caller supplies the facts; unit tested at the edges.
 */
public final class ResponsePolicy {

    /** N-88 (D14): the bands others see. A number is never shown; these are words chosen from the count of OTHERS. */
    public enum Band { SOMEONE, A_FEW, MANY }

    /** N-88: "A few" is 2 to this many; more is "Many". */
    public static final int A_FEW_UP_TO = 5;

    /** N-92: a plan stays open this long after its time (or after the moment leaves Today when it has no time). */
    public static final Duration PLAN_OPEN_AFTER = Duration.ofHours(24);

    /** N-91: a private reply stays open this long after the moment leaves Today; read-only afterwards. */
    public static final Duration REPLY_OPEN_AFTER = Duration.ofHours(24);

    /** N-89, N-91, N-92: the words of a follow-up or a message. */
    public static final int TEXT_MAX = 500;

    public static final String NOT_ON_THIS_KIND = "That response is not available on this moment";
    public static final String NOT_ON_TODAY = "This moment is no longer on Today";
    public static final String OWN_MOMENT = "This is your own moment";
    public static final String ASKS_CLOSED = "This plan is not taking new people";
    public static final String CANNOT_ASK_AGAIN = "You can't ask to join this plan again";
    public static final String MORE_NOT_ASKED = "Someone has to ask you to say more first";
    public static final String MORE_ONCE = "You have already said more";

    private ResponsePolicy() {
    }

    /** N-88, N-89: Same and Say more are on a Say only (a Who's in? has I'm in). */
    public static boolean fits(SignalKind signal, Kind kind) {
        return kind == Kind.SAY;
    }

    /**
     * N-88: the band for [others] = the people OTHER than the viewer who said Same (never the author, who gets names).
     * Null when nobody else did.
     */
    public static Band band(int others) {
        if (others <= 0) return null;
        if (others == 1) return Band.SOMEONE;
        return others <= A_FEW_UP_TO ? Band.A_FEW : Band.MANY;
    }

    /**
     * N-90: whether a Who's in? takes new people at [now]: it is live, not called off, and its time (if it has one) has
     * not come yet. Without a time it takes people while it is on Today.
     */
    public static boolean asksOpen(boolean live, boolean calledOff, LocalDateTime planAt, LocalDateTime now) {
        return live && !calledOff && (planAt == null || planAt.isAfter(now));
    }

    /**
     * N-92: when a plan closes: 24 h after its time, or 24 h after the moment leaves Today when it has no time. Called
     * off = at once ([calledOffAt]).
     */
    public static LocalDateTime planClosesAt(LocalDateTime planAt, LocalDateTime expiresAt, LocalDateTime calledOffAt) {
        if (calledOffAt != null) return calledOffAt;
        return (planAt != null ? planAt : expiresAt).plus(PLAN_OPEN_AFTER);
    }

    /** N-91: when a private reply about a moment that leaves Today at [expiresAt] becomes read-only. */
    public static LocalDateTime replyClosesAt(LocalDateTime expiresAt) {
        return expiresAt.plus(REPLY_OPEN_AFTER);
    }

    /**
     * N-92: whether "This happened" can be shared for a plan at [now]: the plan is open (not closed, not called off)
     * and its time has come (a plan without a time: any time while it is open).
     */
    public static final String HAPPENED_NEEDS_PHOTO = "This happened is a photo taken now, with words if you like";
    public static final String HAPPENED_NOT_OPEN = "This happened can be shared after the plan's time, while the plan is open";
    public static final String HAPPENED_ONCE = "Someone in this plan already shared that it happened";
    public static final String HAPPENED_AUTHOR_ONLY = "Only the person who made a My people plan can share that it happened";
    public static final String HAPPENED_AUDIENCE = "This happened is shared with the plan's audience";

    public static boolean happenedOpen(LocalDateTime planAt, LocalDateTime closesAt, boolean calledOff,
                                       LocalDateTime now) {
        if (calledOff || !closesAt.isAfter(now)) return false;
        return planAt == null || !planAt.isAfter(now);
    }
}
