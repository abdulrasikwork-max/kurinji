package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.FlagReason;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Assessment;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What abuse flags do on their own, before any human looks (Phase 7, pure; FlagPolicyTest).
 *
 *   counted flag   = not resolved, countsFrom passed (new-account probation over), weight > 0
 *   IF / WF        = independent flaggers / their weight in half-points (Independence, linkage of flaggers)
 *   open a case    = at least one counted flag (one case per signal; flags only ever AGGREGATE into it)
 *   quarantine     = IF >= 2 and WF >= 4 and WF >= the signal's weighted support
 *
 * Quarantine only HIDES the signal until a moderator decides; it is fully reversible and never touches a person's
 * standing. The last condition means a well-corroborated signal (for example many independent people confirming a
 * harassment hotspot) cannot be silenced by two flags: it goes to review but stays visible meanwhile. Flags never
 * restrict anyone automatically.
 */
public final class FlagPolicy {

    static final int QUARANTINE_MIN_FLAGGERS = 2;
    static final int QUARANTINE_MIN_WEIGHT = 4;

    private FlagPolicy() {
    }

    /** One flag as the policy needs it (no id leaves here; [participant] only for the independence rule). */
    public record FlagVote(FlagReason reason, LocalDateTime countsFrom, int weight, Long participant, boolean resolved)
            implements Independence.Participant {
    }

    public record Outcome(boolean openCase, boolean quarantine, int independentFlaggers, int weightedFlags) {
    }

    public static Outcome evaluate(List<FlagVote> flags, Assessment assessment, Linkage flagLinkage, LocalDateTime now) {
        List<FlagVote> counted = new ArrayList<>();
        for (FlagVote flag : flags) {
            if (flag.resolved() || flag.countsFrom().isAfter(now) || flag.weight() <= 0) continue;
            counted.add(flag);
        }
        List<FlagVote> independent = Independence.select(counted, flagLinkage);
        int weighted = independent.stream().mapToInt(FlagVote::weight).sum();
        boolean quarantine = independent.size() >= QUARANTINE_MIN_FLAGGERS
                && weighted >= QUARANTINE_MIN_WEIGHT
                && weighted >= assessment.weightedSupport();
        return new Outcome(!counted.isEmpty(), quarantine, independent.size(), weighted);
    }
}
