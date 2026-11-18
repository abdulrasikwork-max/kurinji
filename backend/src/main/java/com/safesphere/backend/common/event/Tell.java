package com.safesphere.backend.common.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * N7 (D26, D29 = N-118, N-121): "these people should hear about this". Published INSIDE the writing transaction by the
 * package that owns the change (social, auth, community, moderation, safety); the push package listens AFTER COMMIT, so
 * a write that rolls back never notifies anyone, and no publisher knows that pushes exist (isolation stays).
 *
 * Carries no words, names or places: a closed kind, user ids (in the JVM only, never sent) and one opaque subject id
 * that the phone uses to read the details through the normal, visibility-checked routes. [recipients] may be empty for
 * [Kind#STAFF_WAITING] (the push package finds the staff who asked for it).
 */
public record Tell(Kind kind, List<Long> recipients, String subject, Instant notBefore, boolean forget) {

    /** The same names as push.entity.PushType (TellKindsTest keeps them equal). */
    public enum Kind {
        PLAN_ASK, PLAN_ADDED, PLAN_MESSAGE, PLAN_CALLED_OFF, REPLY_TURN, MORE_ADDED, PEOPLE_REQUEST,
        ACCOUNT_SIGN_IN, MEMBERSHIP_DECIDED, CONTENT_DECIDED, CIRCLE_REQUEST, CIRCLE_SOS, OMW_REMINDER, STAFF_WAITING
    }

    public Tell(Kind kind, List<Long> recipients, String subject) {
        this(kind, recipients, subject, null, false);
    }

    public Tell {
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
    }

    public static Tell to(Kind kind, long recipient, String subject) {
        return new Tell(kind, List.of(recipient), subject);
    }

    public static Tell to(Kind kind, Collection<Long> recipients, String subject) {
        return new Tell(kind, List.copyOf(recipients), subject);
    }

    /** Tell [recipient] at a later moment (On my way: the due time and the nudge; an SOS: when its texts are due). */
    public static Tell at(Kind kind, long recipient, String subject, Instant when) {
        return new Tell(kind, List.of(recipient), subject, when, false);
    }

    /** What was planned for [recipient] about [subject] and has not gone out yet is no longer wanted. */
    public static Tell forget(Kind kind, long recipient, String subject) {
        return new Tell(kind, List.of(recipient), subject, null, true);
    }
}
