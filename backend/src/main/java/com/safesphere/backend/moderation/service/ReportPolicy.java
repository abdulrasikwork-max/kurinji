package com.safesphere.backend.moderation.service;

import com.safesphere.backend.community.service.Independence;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 13D (pure; ReportPolicyTest): what reports about one post, perspective or person add up to.
 *
 *   counting     a report counts once it is past its counts_from (a new account's probation, M5), while it is not
 *                yet resolved by a moderator, and while its reporter is not paused;
 *   independent  counting reporters picked with the Phase 7 Independence rule: two reporters who keep reporting the
 *                same things (>= 3 shared targets, >= half of the smaller one's reports) count once;
 *   collapse     [threshold] independent counting reports collapse a post or perspective while it waits (M6). People
 *                reports never act by themselves;
 *   priority     the queue order: the most severe reason reported, times the independent counting reports (at least
 *                one), so a single threat comes before two spam reports; ties: older case first (the caller).
 *
 * The reported person never sees any of this; a moderator sees the numbers with case pseudonyms (CaseRefs).
 */
public final class ReportPolicy {

    private ReportPolicy() {
    }

    /** One report as the counting needs it. */
    public record Vote(long reporterId, ModerationReason reason, LocalDateTime countsFrom, boolean resolved,
                       boolean reporterPaused) implements Independence.Participant {

        @Override
        public Long participant() {
            return reporterId;
        }

        @Override
        public int weight() {
            return 1;
        }

        boolean counts(LocalDateTime now) {
            return !resolved && !reporterPaused && !countsFrom.isAfter(now);
        }
    }

    /** What the reports add up to (moderators only). [topReason] null when there is no open report. */
    public record Tally(int open, int counting, int independent, ModerationReason topReason,
                        Map<ModerationReason, Integer> reasons) {
    }

    /** How serious a reason is: 3 for harm to a person's safety, 2 for abuse, 1 for nuisance. Also a strike's weight. */
    public static int severity(ModerationReason reason) {
        return switch (reason) {
            case THREAT, SEXUAL_HARASSMENT, HATE, DOXXING -> 3;
            case HARASSMENT, NUDITY_OR_VIOLENCE, IMPERSONATION -> 2;
            case SPAM, OTHER, NEEDS_REVIEW -> 1;
        };
    }

    public static Tally tally(List<Vote> votes, Independence.Linkage linkage, LocalDateTime now) {
        Map<ModerationReason, Integer> reasons = new EnumMap<>(ModerationReason.class);
        List<Vote> counting = new ArrayList<>();
        int open = 0;
        for (Vote vote : votes) {
            if (vote.resolved()) continue;
            open++;
            reasons.merge(vote.reason(), 1, Integer::sum);
            if (vote.counts(now)) counting.add(vote);
        }
        int independent = Independence.select(counting, linkage).size();
        ModerationReason top = null;
        for (Map.Entry<ModerationReason, Integer> e : reasons.entrySet()) {
            if (top == null || severity(e.getKey()) > severity(top)
                    || (severity(e.getKey()) == severity(top) && e.getValue() > reasons.get(top))) {
                top = e.getKey();
            }
        }
        return new Tally(open, counting.size(), independent, top, reasons);
    }

    /** M6: whether a post or perspective is collapsed while it waits for a moderator. */
    public static boolean collapses(Tally tally, int threshold) {
        return tally.independent() >= threshold;
    }

    /** The queue order (higher first). */
    public static int priority(Tally tally) {
        if (tally.topReason() == null) return 0;
        return severity(tally.topReason()) * Math.max(1, tally.independent());
    }
}
