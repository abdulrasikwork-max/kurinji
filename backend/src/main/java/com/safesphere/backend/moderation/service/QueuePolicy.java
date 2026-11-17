package com.safesphere.backend.moderation.service;

import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOpenedBy;
import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
import com.safesphere.backend.social.entity.ContentModeration.ModerationSource;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Phase N4 (M4, N-69; pure, QueuePolicyTest): the order of the moderators' queue. Before N4 a case that a rule opened
 * had no report and therefore priority 0: it sat under every reported case, however serious. Now every open case is in
 * one of three tiers, and a case that has waited too long moves up one:
 *
 *   tier 1  "This is about me" (a person says a moment is about them: it is hidden, they are waiting), and content a
 *           rule HELD for a severe finding (it is hidden; if the rule was wrong, someone is being silenced);
 *   tier 2  content people reported, ordered inside the tier by the most severe reason times the independent counting
 *           reports (ReportPolicy.priority), so one threat comes before two spam reports;
 *   tier 3  a new member's photo waiting for a first look, and an author asking for a review of a collapse.
 *
 * A case waiting more than 12 hours moves up one tier (never above tier 1), so nothing starves at the bottom. Inside a
 * tier with the same score the older case comes first (the caller sorts by the opening time).
 *
 * The number is only an order. It is shown to moderators with the waiting time, never to anyone else.
 */
public final class QueuePolicy {

    /** A case waiting longer than this moves up one tier. */
    public static final Duration ESCALATE_AFTER = Duration.ofHours(12);

    private static final int TIER = 1000;

    private QueuePolicy() {
    }

    /**
     * What the order needs about one open case. [state], [source] and [reason] are the moment's (null for a person);
     * [reportScore] = ReportPolicy.priority of its open reports (0 when there is none).
     */
    public record Facts(CaseOpenedBy origin, ModerationState state, ModerationSource source, ModerationReason reason,
                        int reportScore, LocalDateTime openedAt) {
    }

    /** 1 (first) to 3, before the waiting time is counted. */
    public static int baseTier(Facts f) {
        if (f.origin() == CaseOpenedBy.ABOUT_ME || f.reason() == ModerationReason.ABOUT_ME) return 1;
        boolean heldByRule = f.state() == ModerationState.HELD && f.source() == ModerationSource.SYSTEM;
        if (heldByRule && f.reason() != ModerationReason.NEW_MEMBER_PHOTO
                && f.reason() != ModerationReason.AWAITING_CHECK) {
            return 1;
        }
        if (f.reportScore() > 0) return 2;
        return 3;
    }

    /** The tier at [now]: one higher once the case has waited more than 12 hours. */
    public static int tier(Facts f, LocalDateTime now) {
        int tier = baseTier(f);
        boolean waitedLong = Duration.between(f.openedAt(), now).compareTo(ESCALATE_AFTER) > 0;
        return waitedLong ? Math.max(1, tier - 1) : tier;
    }

    /** The queue order: higher first. The tier always outweighs the score inside a tier. */
    public static int priority(Facts f, LocalDateTime now) {
        return (4 - tier(f, now)) * TIER + Math.min(Math.max(f.reportScore(), 0), TIER - 1);
    }

    /** Whole hours a case has waited at [now] (shown to moderators in the queue). */
    public static int waitingHours(LocalDateTime openedAt, LocalDateTime now) {
        long hours = Duration.between(openedAt, now).toHours();
        return (int) Math.max(0, Math.min(hours, 24L * 365));
    }
}
