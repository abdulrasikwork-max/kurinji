package com.safesphere.backend.moderation.service;

import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.service.CommunityService.VisibleSignal;
import com.safesphere.backend.community.service.CoordinationPolicy;
import com.safesphere.backend.community.service.CoordinationPolicy.Backing;
import com.safesphere.backend.community.service.CoordinationPolicy.Group;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalVisibility;
import com.safesphere.backend.community.service.TrustService;
import com.safesphere.backend.community.service.TrustService.Snapshot;
import com.safesphere.backend.config.CoordinationProperties;
import com.safesphere.backend.config.CoordinationProperties.Mode;
import com.safesphere.backend.evaluation.service.CaseTrigger;
import com.safesphere.backend.evaluation.service.OutcomeRecorder;
import com.safesphere.backend.moderation.entity.ModerationCase;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOutcome;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.CoordinationRule;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs coord-1 (CoordinationPolicy) over every visible signal, from the periodic clean-up (ModerationService.maintain),
 * at most once per app.moderation.coordination.interval. Phase 9C, docs/PHASE9_DESIGN.md §7.5.1.
 *
 * Its ONLY possible effect is opening a COORDINATION review case for a human, and only in ACTIVE mode (besides Phase
 * 9B's weekly, system-wide counts of what it found or opened, which hold no place, signal or person). It never hides
 * content, never changes a confidence or a weight, never touches a person's standing and never tells anyone (such a
 * case does not even lock withdrawal: ModerationService.blocksWithdrawal). In SHADOW mode (the default) it opens
 * nothing and logs only how many groups it would have put in front of a moderator. The graph it builds from account
 * keys lives in memory for one run; nothing is stored and no log line holds a cell, a category or an account.
 *
 * A group is not asked about again while any of its signals has an OPEN case (a human is already looking), or while a
 * signal backed only by its members has a DISMISSED COORDINATION case (a human found it fine). That mark is the closed
 * case row: kept 90 days, but deleted with its signal, so once the dismissed place itself has ended (3-60 days) a
 * pattern that still holds can be put in front of a moderator once more.
 */
@Component
public class CoordinationCheck {

    private static final Logger log = LoggerFactory.getLogger(CoordinationCheck.class);

    /** What one run found (counts only). */
    public record Run(int candidates, int opened) {
        static final Run SKIPPED = new Run(0, 0);
    }

    private final SignalVisibility visibility;
    private final TrustService trust;
    private final ModerationCaseRepository cases;
    private final CoordinationProperties properties;
    private final OutcomeRecorder outcomes;
    /** In memory: after a restart the check simply runs once more. */
    private Instant lastRun;

    public CoordinationCheck(SignalVisibility visibility, TrustService trust, ModerationCaseRepository cases,
                             CoordinationProperties properties, OutcomeRecorder outcomes) {
        this.visibility = visibility;
        this.trust = trust;
        this.cases = cases;
        this.properties = properties;
        this.outcomes = outcomes;
    }

    /** Called inside ModerationService.maintain's transaction. */
    public synchronized Run run(LocalDateTime now) {
        if (properties.mode() == Mode.OFF) return Run.SKIPPED;
        Instant at = now.toInstant(ZoneOffset.UTC);
        // A last run "in the future" means the clock was set back (NTP correction): run rather than pause for hours.
        if (lastRun != null && !at.isBefore(lastRun) && at.isBefore(lastRun.plus(properties.interval()))) {
            return Run.SKIPPED;
        }
        lastRun = at;

        Findings found = findings(now);
        List<Group> groups = new ArrayList<>(found.groups());
        groups.sort(Comparator.comparingInt((Group g) -> -g.signals().size()).thenComparingLong(Group::target));
        boolean active = properties.mode() == Mode.ACTIVE;
        long openedToday = active ? cases.countByOriginAndOpenedAtAfter(CaseOrigin.COORDINATION, now.minusHours(24)) : 0;
        int candidates = 0;
        int opened = 0;
        for (Group group : groups) {
            List<Long> ids = group.signals().stream().map(Backing::signalId).toList();
            if (cases.existsBySignalIdInAndStatus(ids, CaseStatus.OPEN)) continue;
            // "Found fine" is looked for on every visible signal backed ONLY by this group's members, not just on the
            // ones that make up the finding today: a dismissed place someone withdrew from (one backer left) has left
            // the finding, yet the group was still found fine. Signals non-members back too do not count, so a ring
            // cannot shelter behind an honest place a moderator once dismissed by confirming it (Group.members).
            Set<Long> members = group.members();
            List<Long> theirs = found.backings().stream()
                    .filter(b -> !b.supporters().isEmpty() && members.containsAll(b.supporters()))
                    .map(Backing::signalId).toList();
            if (cases.existsBySignalIdInAndOriginAndOutcome(theirs, CaseOrigin.COORDINATION, CaseOutcome.DISMISSED)) {
                continue;
            }
            candidates++;
            CaseTrigger rule = CaseTrigger.valueOf(group.rule().name());
            if (!active) outcomes.coordinationShadow(rule, ids); // Phase 9B: counted once per group and week
            if (active && openedToday + opened < properties.maxCasesPerDay()) {
                cases.saveAndFlush(new ModerationCase(group.target(), CaseOrigin.COORDINATION,
                        CoordinationRule.valueOf(group.rule().name()), now));
                outcomes.caseOpened(rule); // Phase 9B, after commit
                opened++;
            }
        }
        if (candidates > 0) {
            // Counts only: never a cell, a category with a place, or an account.
            log.info("Coordination check {} ({}): {} group(s) for review, {} case(s) opened",
                    CoordinationPolicy.VERSION, properties.mode(), candidates, opened);
        }
        return new Run(candidates, opened);
    }

    /** The group a signal belongs to right now, for a moderator looking at a case about it (empty when OFF). */
    @Transactional(readOnly = true)
    public Optional<Group> groupOf(long signalId, LocalDateTime now) {
        return current(now).stream().filter(g -> g.contains(signalId)).findFirst();
    }

    /** Every current finding for moderators' case views (empty when OFF, as if coord-1 did not exist). */
    @Transactional(readOnly = true)
    public List<Group> current(LocalDateTime now) {
        if (properties.mode() == Mode.OFF) return List.of();
        return groups(now);
    }

    /** Every current finding (also read by the Phase 9A simulation, so it measures the production rules). */
    @Transactional(readOnly = true)
    public List<Group> groups(LocalDateTime now) {
        return findings(now).groups();
    }

    /** One run's input (who counts behind every visible signal) and what the rules found in it. In memory only. */
    private record Findings(List<Backing> backings, List<Group> groups) {
    }

    private Findings findings(LocalDateTime now) {
        List<VisibleSignal> visible = visibility.everywhere(now);
        if (visible.isEmpty()) return new Findings(List.of(), List.of());
        Snapshot snapshot = trust.snapshot(visible.stream().flatMap(v -> v.contributions().stream()).toList(), now);
        List<Backing> backings = visible.stream().map(v -> backing(v, snapshot, now)).toList();
        // support() is Phase 7's PEOPLE-level linkage: a moderator's per-signal dismissal (Phase 9C.1) changes how that
        // one signal counts, not which pairs R2 leaves to R0 (the pair is still linked on its other signals).
        return new Findings(backings, CoordinationPolicy.groups(backings, snapshot.support()));
    }

    /** The accounts that COUNT behind a signal now: support, past delay and probation, within lifetime, weight > 0. */
    private static Backing backing(VisibleSignal v, Snapshot snapshot, LocalDateTime now) {
        PlaceSignal signal = v.signal();
        LocalDateTime oldestAlive = now.minus(signal.getCategory().lifetime());
        Set<Long> supporters = v.contributions().stream()
                .filter(c -> c.getKind().supports() && !c.getCountsFrom().isAfter(now)
                        && c.getCountsFrom().isAfter(oldestAlive) && snapshot.weight(c.getUserId()) > 0)
                .map(SignalContribution::getUserId)
                .collect(Collectors.toUnmodifiableSet());
        return new Backing(signal.getId(), signal.getCategory(), signal.getAreaCell(),
                v.assessment().confidence() != Confidence.LOW, supporters);
    }
}
