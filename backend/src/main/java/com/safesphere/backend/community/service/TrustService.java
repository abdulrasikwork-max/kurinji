package com.safesphere.backend.community.service;

import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.ContributorStanding;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.entity.SignalFlag;
import com.safesphere.backend.community.repository.ContributorStandingRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Assessment;
import com.safesphere.backend.community.service.SignalPolicy.Vote;
import com.safesphere.backend.community.service.TrustPolicy.Standing;
import com.safesphere.backend.community.service.TrustPolicy.Tier;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOutcome;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 7 trust, server side only (docs/PHASE7.md §3). Loads what the pure policies need for one request (tiers and
 * linkage of the people involved), keeps the private standing counters and halves them every 90 days. Nothing here is
 * ever returned to a client: the only visible effects are a signal's confidence, precision and visibility.
 */
@Service
public class TrustService {

    /** Counters halve once per this period (a 90-day half-life). */
    static final Duration DECAY_PERIOD = Duration.ofDays(90);

    private final ContributorStandingRepository standings;
    private final SignalContributionRepository contributions;
    private final SignalFlagRepository flags;
    private final UserRepository users;
    private final ModerationCaseRepository cases;

    public TrustService(ContributorStandingRepository standings, SignalContributionRepository contributions,
                        SignalFlagRepository flags, UserRepository users, ModerationCaseRepository cases) {
        this.standings = standings;
        this.contributions = contributions;
        this.flags = flags;
        this.users = users;
        this.cases = cases;
    }

    /**
     * What the policies need about the people in one request. Weights are in half-points. [supportCleared] (Phase
     * 9C.1): signal id → when a moderator last DISMISSED a COORDINATION case about it (see [supportOn]).
     */
    public record Snapshot(Map<Long, Tier> tiers, Map<Long, Standing> standingsById, Map<Long, LocalDateTime> createdAt,
                           Linkage support, Linkage dispute, Linkage flagging, Map<Long, LocalDateTime> supportCleared) {

        /** Whether two people keep acting together on any side (for showing link groups to moderators). */
        public boolean linkedAnyhow(long a, long b) {
            return support.linked(a, b) || dispute.linked(a, b) || flagging.linked(a, b);
        }

        public int weight(long userId) {
            return tiers.getOrDefault(userId, Tier.RESTRICTED).weight(); // unknown account: counts for nothing
        }

        public Tier tier(long userId) {
            return tiers.getOrDefault(userId, Tier.RESTRICTED);
        }

        public int flagWeight(long userId) {
            return TrustPolicy.flagWeight(tier(userId), standingsById.getOrDefault(userId, Standing.NEW));
        }

        public List<Vote> votes(List<SignalContribution> list) {
            return list.stream().map(c -> new Vote(c.getKind(), c.getCountsFrom(), weight(c.getUserId()), c.getUserId()))
                    .toList();
        }

        public Assessment assess(PlaceSignal signal, List<SignalContribution> list, LocalDateTime now) {
            return SignalPolicy.assess(signal.getCategory(), votes(list), now, supportOn(signal.getId(), list), dispute);
        }

        /**
         * Support linkage as it applies to ONE signal (Phase 9C.1, S15 Option B, docs/PHASE9.md §9). Phase 7 counts
         * accounts that keep backing the same things as one person. When a moderator DISMISSED a COORDINATION case
         * about this signal ("these are separate people"), the supporters who were COUNTING at that moment count as
         * separate people here, and only here: every other signal of theirs is linked as before, and anyone who joins
         * this signal later is counted by the normal rule (so one mistaken dismissal cannot be topped up with more
         * linked accounts). Nothing new is stored: the mark is the closed case row, gone after the case retention
         * (90 days) or with the signal.
         */
        public Linkage supportOn(long signalId, Collection<SignalContribution> list) {
            LocalDateTime cleared = supportCleared.get(signalId);
            if (cleared == null) return support;
            Set<Long> seen = new HashSet<>();
            for (SignalContribution c : list) {
                if (c.getSignalId() == signalId && c.getKind().supports() && !c.getCountsFrom().isAfter(cleared)) {
                    seen.add(c.getUserId());
                }
            }
            if (seen.size() < 2) return support;
            return (a, b) -> !(seen.contains(a) && seen.contains(b)) && support.linked(a, b);
        }
    }

    /** Snapshot for everyone who contributed to (or flagged) the given rows. */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Collection<SignalContribution> contributionRows, Collection<SignalFlag> flagRows,
                             LocalDateTime now) {
        return snapshot(contributionRows, flagRows, Set.of(), now);
    }

    /**
     * The same, plus [alsoPeople] who have no row here but whose tier, age and linkage a moderator needs too (Phase
     * 9C: the members of a coordination group who are not behind the case's own signal). They change no assessment:
     * linkage only matters between people who back the assessed signal.
     */
    @Transactional(readOnly = true)
    public Snapshot snapshot(Collection<SignalContribution> contributionRows, Collection<SignalFlag> flagRows,
                             Collection<Long> alsoPeople, LocalDateTime now) {
        Set<Long> contributors = new HashSet<>(alsoPeople);
        Set<Long> signalIds = new HashSet<>();
        contributionRows.forEach(c -> {
            contributors.add(c.getUserId());
            signalIds.add(c.getSignalId());
        });
        Set<Long> flaggers = new HashSet<>();
        flagRows.forEach(f -> flaggers.add(f.getUserId()));
        return snapshotOfPeople(contributors, flaggers, supportCleared(signalIds), now);
    }

    public Snapshot snapshot(Collection<SignalContribution> contributionRows, LocalDateTime now) {
        return snapshot(contributionRows, List.of(), now);
    }

    /** One person's current private tier and counters (for a moderator deciding that person's appeal). */
    @Transactional(readOnly = true)
    public Snapshot snapshotOf(long userId, LocalDateTime now) {
        return snapshotOfPeople(Set.of(userId), Set.of(), Map.of(), now);
    }

    /** Phase 9C.1: when a moderator found the support of each of these signals fine (latest DISMISSED COORDINATION case). */
    private Map<Long, LocalDateTime> supportCleared(Set<Long> signalIds) {
        if (signalIds.isEmpty()) return Map.of();
        Map<Long, LocalDateTime> result = new HashMap<>();
        for (Object[] row : cases.lastClosedBySignal(signalIds, CaseOrigin.COORDINATION, CaseOutcome.DISMISSED)) {
            result.put(((Number) row[0]).longValue(), (LocalDateTime) row[1]);
        }
        return result;
    }

    private Snapshot snapshotOfPeople(Set<Long> contributors, Set<Long> flaggers, Map<Long, LocalDateTime> supportCleared,
                                      LocalDateTime now) {
        Set<Long> everyone = new HashSet<>(contributors);
        everyone.addAll(flaggers);

        Map<Long, Standing> standingById = new HashMap<>();
        Map<Long, Tier> tiers = new HashMap<>();
        Map<Long, LocalDateTime> createdAt = new HashMap<>();
        if (!everyone.isEmpty()) {
            Map<Long, ContributorStanding> rows = new HashMap<>();
            standings.findAllById(everyone).forEach(s -> rows.put(s.getUserId(), s));
            for (User user : users.findAllById(everyone)) {
                ContributorStanding row = rows.get(user.getId());
                Standing standing = row == null ? Standing.NEW : new Standing(row.getCorroborated(), row.getRefuted(),
                        row.getStrikes(), row.getFlagsUpheld(), row.getFlagsRejected(), row.isRestricted(now));
                Duration age = Duration.between(user.getCreatedAt(), now);
                standingById.put(user.getId(), standing);
                tiers.put(user.getId(), TrustPolicy.tier(standing, age, user.isEnabled()));
                createdAt.put(user.getId(), user.getCreatedAt());
            }
        }
        Linkage support = Linkage.NONE;
        Linkage dispute = Linkage.NONE;
        if (contributors.size() >= 2) {
            support = Independence.fromRows(
                    contributions.sharedSupportPairs(contributors, ContributionKind.NO_LONGER, Independence.MIN_SHARED),
                    totals(contributions.supportTotals(contributors, ContributionKind.NO_LONGER)));
            dispute = Independence.fromRows(
                    contributions.sharedDisputePairs(contributors, ContributionKind.NO_LONGER, Independence.MIN_SHARED),
                    totals(contributions.disputeTotals(contributors, ContributionKind.NO_LONGER)));
        }
        Linkage flagging = Linkage.NONE;
        if (flaggers.size() >= 2) {
            flagging = Independence.fromRows(flags.sharedFlagPairs(flaggers, Independence.MIN_SHARED),
                    totals(flags.flagTotals(flaggers)));
        }
        return new Snapshot(tiers, standingById, createdAt, support, dispute, flagging, supportCleared);
    }

    /** True while a moderator has paused this person's community contributions. */
    @Transactional(readOnly = true)
    public boolean isRestricted(long userId, LocalDateTime now) {
        return standings.findById(userId).map(s -> s.isRestricted(now)).orElse(false);
    }

    /** The person's own standing row, created on first need. */
    @Transactional
    public ContributorStanding standingFor(long userId, LocalDateTime now) {
        return standings.findById(userId).orElseGet(() -> standings.save(new ContributorStanding(userId, now)));
    }

    /** Halves the counters of every row last halved more than 90 days ago. Returns how many rows changed. */
    @Transactional
    public int decay(LocalDateTime now) {
        List<ContributorStanding> due = standings.findByDecayedAtLessThanEqual(now.minus(DECAY_PERIOD));
        due.forEach(s -> s.decay(now));
        return due.size();
    }

    private static Map<Long, Long> totals(List<Object[]> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : rows) result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        return result;
    }
}
