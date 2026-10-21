package com.safesphere.backend.community.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.community.dto.CommunityDtos.Accepted;
import com.safesphere.backend.community.dto.CommunityDtos.AreaResponse;
import com.safesphere.backend.community.dto.CommunityDtos.FlagAccepted;
import com.safesphere.backend.community.dto.CommunityDtos.MyContribution;
import com.safesphere.backend.community.dto.CommunityDtos.MyStatus;
import com.safesphere.backend.community.dto.CommunityDtos.PublicSignal;
import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.ContributorStanding;
import com.safesphere.backend.community.entity.FlagReason;
import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.entity.SignalFlag;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.Independence.Linkage;
import com.safesphere.backend.community.service.SignalPolicy.Assessment;
import com.safesphere.backend.community.service.SignalPolicy.Precision;
import com.safesphere.backend.community.service.SignalVisibility.VisibleSet;
import com.safesphere.backend.community.service.TrustService.Snapshot;
import com.safesphere.backend.config.CommunityProperties;
import com.safesphere.backend.config.ModerationProperties;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.evaluation.service.OutcomeRecorder;
import com.safesphere.backend.evaluation.service.OutcomeRecorder.SignalEnd;
import com.safesphere.backend.intelligence.service.AreaIntelligenceService;
import com.safesphere.backend.intelligence.service.PatternService;
import com.safesphere.backend.moderation.service.ModerationService;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Community Safety Intelligence: place signals (docs/PHASE6.md), with Phase 7 trust and moderation (docs/PHASE7.md).
 * Every rule about privacy and abuse lives here or in the pure policies (SignalPolicy, TrustPolicy, Independence,
 * FlagPolicy):
 *   - the caller is only ever the JWT subject; a signal has no owner field anyone can see;
 *   - reports MERGE into the one signal per (cell, category); a person answers OR flags a signal once; since Phase 9D
 *     a lone report also joins the same signal in a block sharing a side with its own, once it counts (purge);
 *   - every contribution starts counting after a random delay (and after a new account's probation);
 *   - a signal is visible only per SignalPolicy (independent people, private weights) and when not under moderation,
 *     and only at the precision it has earned;
 *   - area look-ups are limited to the caller's own neighbourhood and rate-limited (AreaQueryLimiter);
 *   - reports per day / per area / all answers per day / flags per day / bursts are limited (429);
 *   - a person whose contributions a moderator paused may still LOOK (safety information is never withheld) but not
 *     report, answer or flag (403);
 *   - expired and retired signals are DELETED by [purge], not hidden.
 */
@Service
public class CommunityService {

    private static final Duration DAY = Duration.ofHours(24);
    /** Phase 9D: categories whose lone reports may join the same signal next door (concerns; never safe places). */
    private static final Set<SignalCategory> JOINABLE = EnumSet.copyOf(Arrays.stream(SignalCategory.values())
            .filter(c -> c.group() != SignalCategory.SignalGroup.HAVEN).toList());
    static final String PAUSED = "Your community contributions are paused for now. You can still see signals, "
            + "and every other safety feature works as usual.";

    private final PlaceSignalRepository signals;
    private final SignalContributionRepository contributions;
    private final SignalFlagRepository flags;
    private final UserRepository users;
    private final CommunityProperties properties;
    private final ModerationProperties moderationProperties;
    private final AreaQueryLimiter areaLimiter;
    private final TrustService trust;
    private final ModerationService moderation;
    private final Clock clock;
    private final AttemptLimiter writeBursts;
    private final AreaIntelligenceService intelligence;
    private final PatternService patterns;
    private final SignalVisibility visibility;
    private final OutcomeRecorder outcomes;
    private final SecureRandom random = new SecureRandom();

    public CommunityService(PlaceSignalRepository signals, SignalContributionRepository contributions,
                            SignalFlagRepository flags, UserRepository users, CommunityProperties properties,
                            ModerationProperties moderationProperties, AreaQueryLimiter areaLimiter,
                            TrustService trust, ModerationService moderation, Clock clock,
                            AreaIntelligenceService intelligence, PatternService patterns,
                            SignalVisibility visibility, OutcomeRecorder outcomes) {
        this.outcomes = outcomes;
        this.intelligence = intelligence;
        this.patterns = patterns;
        this.visibility = visibility;
        this.signals = signals;
        this.contributions = contributions;
        this.flags = flags;
        this.users = users;
        this.properties = properties;
        this.moderationProperties = moderationProperties;
        this.areaLimiter = areaLimiter;
        this.trust = trust;
        this.moderation = moderation;
        this.clock = clock;
        this.writeBursts = new AttemptLimiter(properties.writesPer10Minutes(), Duration.ofMinutes(10), 100_000, clock);
    }

    // ---- Writing ---------------------------------------------------------------------------------------------

    /**
     * A new report. Merges into the existing signal for this cell and category if there is one. The answer is the
     * same in every case (new, merged, a re-send of the caller's own report, a signal under review), so it reveals
     * nothing about others.
     */
    @Transactional
    public Accepted report(long userId, SignalCategory category, String cell) {
        LocalDateTime now = now();
        requireNotPaused(userId, now);
        PlaceSignal signal = signals.findByCellAndCategory(cell, category).orElse(null);
        if (signal != null) {
            List<SignalContribution> existing = contributions.findBySignalId(signal.getId());
            if (existing.stream().anyMatch(c -> c.getUserId() == userId)
                    || flags.findBySignalIdAndUserId(signal.getId(), userId).isPresent()) {
                // A re-send from the phone's outbox, or someone who flagged this very signal: nothing to add.
                return accepted(userId, now);
            }
            if (signal.isUnmoderated()) {
                Snapshot snapshot = trust.snapshot(existing, now);
                Assessment assessment = snapshot.assess(signal, existing, now);
                if (assessment.retired() || assessment.expired()) {
                    // The old signal was retired ("no longer true") or has run out: this report starts a fresh one.
                    if (assessment.retired()) recordUnsupportedReports(signal, existing, snapshot, now);
                    else endedNaturally(signal);
                    ending(List.of(signal), assessment.retired() ? SignalEnd.RETIRED : SignalEnd.EXPIRED);
                    deleteSignal(signal);
                    signal = null;
                }
            }
            // A QUARANTINED or REMOVED signal keeps the report silently: a removed fake cannot be re-created at once,
            // and independent re-reports reopen a case for a human (ModerationService.maintain).
        }
        if (signal == null && confirmsNextDoor(userId, category, cell)) {
            // Phase 9D: the caller already said "I saw this too" to the same thing one block over (usually this very
            // report, re-sent from the phone's outbox after the clean-up joined it to its neighbour): nothing to add.
            return accepted(userId, now);
        }
        enforceLimits(userId, ContributionKind.REPORT, cell.substring(0, GeoCell.AREA_PRECISION), now);
        burst(userId);
        if (signal == null) {
            signal = signals.saveAndFlush(new PlaceSignal(category, cell));
            outcomes.signalStarted(category); // Phase 9B, after commit
        }
        contributions.saveAndFlush(new SignalContribution(signal.getId(), userId, ContributionKind.REPORT,
                countsFrom(userId, now)));
        return accepted(userId, now);
    }

    /** "I saw this too" / "no longer true" on a VISIBLE signal, from someone near it (by the caller's own cell). */
    @Transactional
    public Accepted contribute(long userId, String publicId, ContributionKind kind, String nearCell) {
        if (kind == ContributionKind.REPORT) {
            throw new InvalidRequestException("Use a new report to add a signal");
        }
        LocalDateTime now = now();
        requireNotPaused(userId, now);
        Visible visible = visibleSignal(publicId, now);
        if (visible.contributions.stream().anyMatch(c -> c.getUserId() == userId)
                || flags.findBySignalIdAndUserId(visible.signal.getId(), userId).isPresent()) {
            throw new ConflictException("You have already added to this signal");
        }
        requireNear(visible, nearCell);
        enforceLimits(userId, kind, null, now);
        burst(userId);
        contributions.saveAndFlush(new SignalContribution(visible.signal.getId(), userId, kind, countsFrom(userId, now)));
        return accepted(userId, now);
    }

    /**
     * Phase 7: "something is wrong with this signal" (false, aimed at someone, spam). Same visibility and nearness
     * rules as answers; one flag per person per signal; never by someone who contributed to it (they withdraw instead).
     * The answer is always the same, whatever the flag caused.
     */
    @Transactional
    public FlagAccepted flag(long userId, String publicId, FlagReason reason, String nearCell) {
        LocalDateTime now = now();
        requireNotPaused(userId, now);
        Visible visible = visibleSignal(publicId, now);
        if (visible.contributions.stream().anyMatch(c -> c.getUserId() == userId)) {
            throw new ConflictException("You added to this signal. To take that back, withdraw your own answer.");
        }
        if (flags.findBySignalIdAndUserId(visible.signal.getId(), userId).isPresent()) {
            throw new ConflictException("You have already flagged this signal");
        }
        requireNear(visible, nearCell);
        if (flags.countByUserIdAndCountsFromAfter(userId, now.minus(DAY)) >= moderationProperties.maxFlagsPerDay()) {
            throw new TooManyAttemptsException("You have reached today's limit for flags. Please try again tomorrow.");
        }
        burst(userId);
        LocalDateTime countsFrom = probationEnd(userId);
        if (countsFrom.isBefore(now)) countsFrom = now;
        flags.saveAndFlush(new SignalFlag(visible.signal.getId(), userId, reason, countsFrom));
        moderation.evaluateFlags(visible.signal, now);
        return new FlagAccepted("RECEIVED");
    }

    /**
     * Withdraws the caller's own contribution. Someone else's (or none) answers 404. While people's complaint about the
     * signal is under review (a FLAGS or REREPORTED case, or flags on any open case), withdrawing is refused (409) so a
     * report under review cannot be pulled to escape the decision; the signal still ends with its normal lifetime.
     * Phase 9C: a case the system opened by itself (COORDINATION) does NOT lock withdrawal, because the 409 would tell
     * a ring it had been detected, and withdrawing removes the fake support anyway (ModerationService.blocksWithdrawal).
     */
    @Transactional
    public void withdraw(long userId, String publicId) {
        PlaceSignal signal = signals.findByPublicId(publicId).orElseThrow(CommunityService::notFound);
        SignalContribution mine = contributions.findBySignalIdAndUserId(signal.getId(), userId)
                .orElseThrow(CommunityService::notFound);
        if (moderation.blocksWithdrawal(signal.getId())) {
            throw new ConflictException("This signal is being reviewed. You can withdraw it after the review.");
        }
        contributions.delete(mine);
        contributions.flush();
        if (contributions.findBySignalId(signal.getId()).isEmpty() && signal.getModeration() != ModerationState.REMOVED) {
            ending(List.of(signal), SignalEnd.WITHDRAWN);
            deleteSignal(signal);
        }
    }

    // ---- Reading ---------------------------------------------------------------------------------------------

    /**
     * Visible signals in the caller's ~1 km area cell and its 8 neighbours (~3.6 x 1.8 km), nearest first. There is
     * no way to ask for a bigger box, a list of everything, or a past time.
     */
    @Transactional(readOnly = true)
    public AreaResponse area(long userId, String area) {
        areaLimiter.check(userId, area);
        LocalDateTime now = now();
        List<String> neighbourhood = List.copyOf(GeoCell.neighbourhood(area));
        VisibleSet set = visibleSetIn(neighbourhood, now);
        List<VisibleSignal> found = set.signals();
        Set<Long> flaggedByCaller = found.isEmpty() ? Set.of()
                : flags.findBySignalIdIn(found.stream().map(v -> v.signal().getId()).toList()).stream()
                        .filter(f -> f.getUserId() == userId).map(SignalFlag::getSignalId).collect(Collectors.toSet());
        double[] origin = GeoCell.centre(area);

        List<PublicSignal> visible = new ArrayList<>();
        for (VisibleSignal v : found) {
            PlaceSignal signal = v.signal();
            Assessment assessment = v.assessment();
            String shownCell = assessment.precision() == Precision.BLOCK ? signal.getCell() : signal.getAreaCell();
            double[] centre = GeoCell.centre(shownCell);
            ContributionKind yours = v.contributions().stream().filter(c -> c.getUserId() == userId)
                    .map(SignalContribution::getKind).findFirst().orElse(null);
            visible.add(new PublicSignal(signal.getPublicId(), signal.getCategory(), signal.getCategory().group(),
                    assessment.precision(), shownCell, centre[0], centre[1], assessment.support(),
                    assessment.freshness(), assessment.disputed(), yours, assessment.confidence(),
                    assessment.reasons(), flaggedByCaller.contains(signal.getId())));
        }
        visible.sort(Comparator.comparingDouble(
                (PublicSignal s) -> GeoCell.distanceMeters(origin[0], origin[1], s.latitude(), s.longitude()))
                .thenComparing(PublicSignal::id));
        List<PublicSignal> limited = visible.size() > properties.maxSignalsPerArea()
                ? visible.subList(0, properties.maxSignalsPerArea()) : visible;
        // Phase 8: the picture is computed from the FULL visible set, before the list is cut for the phone.
        return new AreaResponse(area, List.copyOf(limited), intelligence.neighbourhood(area, neighbourhood, set));
    }

    /**
     * One signal that anyone may currently see, with its contributions and assessment (Phase 8). Only for use inside
     * the server: it carries user ids, so it is never serialised.
     */
    public record VisibleSignal(PlaceSignal signal, List<SignalContribution> contributions, Assessment assessment) {
    }

    /**
     * What anyone may see in these area cells: the ONE visibility definition, which lives in SignalVisibility (Phase 8
     * extracted it so that Safety Intelligence reads exactly the same set; Phase 9C moved it so moderation can too).
     * Pending (delayed), quarantined, removed, retired and expired signals never appear here.
     */
    @Transactional(readOnly = true)
    public List<VisibleSignal> visibleIn(Collection<String> areaCells, LocalDateTime now) {
        return visibility.in(areaCells, now);
    }

    /** The same set, plus the people-level linkage among everyone behind it (Phase 9D, for rules-2). */
    @Transactional(readOnly = true)
    public VisibleSet visibleSetIn(Collection<String> areaCells, LocalDateTime now) {
        return visibility.setIn(areaCells, now);
    }

    /** The server's current time as stored (UTC, microseconds); Phase 8 reads use the same clock. */
    public LocalDateTime currentTime() {
        return now();
    }

    /** The caller's own live contributions (OWNER view), so they can see what they shared and withdraw it. */
    @Transactional(readOnly = true)
    public List<MyContribution> mine(long userId) {
        LocalDateTime now = now();
        List<SignalContribution> own = contributions.findByUserId(userId);
        if (own.isEmpty()) return List.of();
        Map<Long, PlaceSignal> byId = signals.findAllById(own.stream().map(SignalContribution::getSignalId).toList())
                .stream().collect(Collectors.toMap(PlaceSignal::getId, Function.identity()));
        Map<Long, List<SignalContribution>> bySignal = contributionsOf(byId.values());
        Snapshot snapshot = trust.snapshot(bySignal.values().stream().flatMap(List::stream).toList(), now);

        List<MyContribution> result = new ArrayList<>();
        for (SignalContribution contribution : own) {
            PlaceSignal signal = byId.get(contribution.getSignalId());
            if (signal == null) continue;
            if (!contribution.getCountsFrom().isAfter(now.minus(signal.getCategory().lifetime()))) continue; // ran out
            Assessment assessment = snapshot.assess(signal, bySignal.getOrDefault(signal.getId(), List.of()), now);
            MyStatus status = switch (signal.getModeration()) {
                case QUARANTINED -> MyStatus.UNDER_REVIEW;
                case REMOVED -> MyStatus.NOT_SHOWN;
                case NONE -> contribution.getCountsFrom().isAfter(now) ? MyStatus.PENDING
                        : assessment.visible() ? MyStatus.LIVE : MyStatus.NOT_SHOWN;
            };
            // The person's own report: its own cell. A confirmation: only the precision everyone can see.
            String cell = contribution.getKind() == ContributionKind.REPORT || assessment.precision() == Precision.BLOCK
                    ? signal.getCell() : signal.getAreaCell();
            result.add(new MyContribution(signal.getPublicId(), signal.getCategory(), contribution.getKind(), cell,
                    status));
        }
        result.sort(Comparator.comparing(MyContribution::status).thenComparing(MyContribution::signalId));
        return result;
    }

    // ---- Retention and private standing ----------------------------------------------------------------------------

    /** What one clean-up run did (counts only, for the job's log line). */
    public record Cleanup(int removed, int joined) {
    }

    /**
     * Minimum retention plus Phase 7 bookkeeping, run periodically by CommunityPurgeJob:
     *   0. (Phase 9D) moves lone reports onto the same signal one block over ([joinLoneReports]);
     *   1. credits reports that now have INDEPENDENT support to their reporters' private standing (once per report);
     *   2. deletes contributions whose category lifetime is over;
     *   3. deletes signals retired by "no longer true" (a retired report nobody independent ever backed counts as
     *      "refuted" for its reporter);
     *   4. deletes signals left with no contribution (a removed signal's tombstone only after its hold).
     * Phase 9B: joins, first confirmations and every deletion are also counted (OutcomeRecorder, after commit).
     */
    @Transactional
    public Cleanup purge() {
        LocalDateTime now = now();
        int removed = 0;
        int joined = joinLoneReports(now);
        creditCorroboratedReports(now);
        markReachedConfirmed(now);
        for (SignalCategory category : SignalCategory.values()) {
            removed += contributions.deleteExpired(category, now.minus(category.lifetime()));
        }
        List<Long> disputed = contributions.signalIdsWithKind(ContributionKind.NO_LONGER);
        if (!disputed.isEmpty()) {
            List<PlaceSignal> candidates = signals.findAllById(disputed).stream().filter(PlaceSignal::isUnmoderated).toList();
            Map<Long, List<SignalContribution>> bySignal = contributionsOf(candidates);
            Snapshot snapshot = trust.snapshot(bySignal.values().stream().flatMap(List::stream).toList(), now);
            List<PlaceSignal> retired = new ArrayList<>();
            for (PlaceSignal signal : candidates) {
                List<SignalContribution> list = bySignal.getOrDefault(signal.getId(), List.of());
                if (snapshot.assess(signal, list, now).retired()) {
                    recordUnsupportedReports(signal, list, snapshot, now);
                    retired.add(signal);
                }
            }
            if (!retired.isEmpty()) {
                List<Long> ids = retired.stream().map(PlaceSignal::getId).toList();
                ending(retired, SignalEnd.RETIRED);
                removed += contributions.deleteBySignalIds(ids);
                signals.deleteAllByIdInBatch(ids);
            }
        }
        signals.findEndedConfirmed(ModerationState.NONE).forEach(this::endedNaturally);
        // Phase 9B: what deleteEmpty is about to delete. A removed signal's tombstone was counted when it was removed;
        // everything else here ran out (its contributions expired).
        List<PlaceSignal> empty = signals.findEmpty(ModerationState.REMOVED, now);
        ending(empty.stream().filter(s -> s.getModeration() != ModerationState.REMOVED).toList(), SignalEnd.EXPIRED);
        moderation.countUnreviewed(empty.stream().filter(s -> s.getModeration() == ModerationState.REMOVED)
                .map(PlaceSignal::getId).toList());
        removed += signals.deleteEmpty(ModerationState.REMOVED, now);
        areaLimiter.evictExpired();
        writeBursts.evictExpired();
        return new Cleanup(removed, joined);
    }

    /**
     * Phase 9D (docs/PHASE9.md §18): a LONE report joins the same signal one block over. Two honest people who report
     * one dark street from neighbouring ~150 m blocks used to stay two "not yet confirmed" signals (F7); now the newer
     * lone report is moved, as a confirmation, onto an older signal of the SAME category in a block that shares a SIDE
     * with its own. Never a corner block: measured, corners join four times more different places than honest pairs
     * they add. It happens here, only once the report COUNTS, so nobody can use it to find where a single report is
     * without their own report becoming public support (what "I saw this too" gives anyway). Only:
     *   - a lone report: the signal's ONLY contribution, a REPORT that counts, from someone who counts (weight > 0),
     *     about a concern (incident or condition; safe places are points and never join: lure guard), with no flag,
     *     no open case, and never confirmed before (a possible pattern episode stays where it is);
     *   - into an OLDER signal (nothing older ever moves, so a newer signal cannot capture an existing one) that anyone
     *     may see now, with no open case, and that the reporter has not answered or flagged;
     *   - best target: most independent people behind it, then the oldest.
     * The contribution keeps its countsFrom and becomes a CONFIRM, so "your reports" shows it as "you said you saw this
     * too" at the precision everyone sees (never the hidden block of a single report). Independence, weights,
     * probation and k = 2 then apply to it like to any confirmation.
     */
    private int joinLoneReports(LocalDateTime now) {
        List<PlaceSignal> lone = new ArrayList<>(signals.findLoneReports(ModerationState.NONE, JOINABLE,
                ContributionKind.REPORT, now));
        if (lone.isEmpty()) return 0;
        lone.sort(Comparator.comparing(PlaceSignal::getId).reversed()); // the newest joins first
        Map<Long, List<SignalContribution>> loneRows = contributionsOf(lone);
        Set<String> areas = new HashSet<>();
        lone.forEach(s -> sides(s.getCell()).forEach(c -> areas.add(c.substring(0, GeoCell.AREA_PRECISION))));
        List<VisibleSignal> nextDoor = visibleIn(areas, now);
        if (nextDoor.isEmpty()) return 0;

        Snapshot snapshot = trust.snapshot(loneRows.values().stream().flatMap(List::stream).toList(), now);
        Set<Long> ids = new HashSet<>();
        lone.forEach(s -> ids.add(s.getId()));
        nextDoor.forEach(v -> ids.add(v.signal().getId()));
        Set<Long> underReview = moderation.underReview(ids);
        Map<Long, Set<Long>> involved = new HashMap<>(); // signal id -> everyone who answered or flagged it
        nextDoor.forEach(v -> v.contributions().forEach(c ->
                involved.computeIfAbsent(c.getSignalId(), k -> new HashSet<>()).add(c.getUserId())));
        Set<Long> flagged = new HashSet<>();
        for (SignalFlag f : flags.findBySignalIdIn(ids)) {
            flagged.add(f.getSignalId());
            involved.computeIfAbsent(f.getSignalId(), k -> new HashSet<>()).add(f.getUserId());
        }

        int joined = 0;
        Set<Long> received = new HashSet<>();
        for (PlaceSignal signal : lone) {
            List<SignalContribution> rows = loneRows.getOrDefault(signal.getId(), List.of());
            if (rows.size() != 1 || received.contains(signal.getId()) || flagged.contains(signal.getId())
                    || underReview.contains(signal.getId())) continue;
            SignalContribution report = rows.get(0);
            long reporter = report.getUserId();
            if (!report.getCountsFrom().isAfter(now.minus(signal.getCategory().lifetime()))
                    || snapshot.weight(reporter) <= 0) continue;
            VisibleSignal target = nextDoor.stream()
                    .filter(v -> v.signal().getCategory() == signal.getCategory()
                            && v.signal().getId() < signal.getId()
                            && GeoCell.adjacency(signal.getCell(), v.signal().getCell()) == GeoCell.Adjacency.EDGE
                            && !underReview.contains(v.signal().getId())
                            && !involved.getOrDefault(v.signal().getId(), Set.of()).contains(reporter))
                    .min(Comparator.comparingInt((VisibleSignal v) -> -v.assessment().supporters())
                            .thenComparing(v -> v.signal().getId()))
                    .orElse(null);
            if (target == null) continue;
            long targetId = target.signal().getId();
            deleteSignal(signal);
            contributions.saveAndFlush(new SignalContribution(targetId, reporter, ContributionKind.CONFIRM,
                    report.getCountsFrom()));
            involved.computeIfAbsent(targetId, k -> new HashSet<>()).add(reporter);
            received.add(targetId);
            outcomes.signalJoined(signal.getCategory()); // Phase 9B, after commit
            joined++;
        }
        return joined;
    }

    /**
     * Phase 9D: whether the caller CONFIRMS this category in a block that shares a side with [cell] (a joined report
     * is such a confirmation). Their own REPORT next door does not count: someone walking a dark street may report
     * two blocks of it, as before 9D; the two never join each other (one person never confirms themself).
     */
    private boolean confirmsNextDoor(long userId, SignalCategory category, String cell) {
        if (!JOINABLE.contains(category)) return false;
        return signals.findByCellInAndCategory(sides(cell), category).stream()
                .anyMatch(s -> contributions.findBySignalIdAndUserId(s.getId(), userId)
                        .map(c -> c.getKind() == ContributionKind.CONFIRM).orElse(false));
    }

    /** The (up to) four blocks that share a side with [cell]. */
    private static List<String> sides(String cell) {
        return GeoCell.neighbourhood(cell).stream()
                .filter(c -> GeoCell.adjacency(cell, c) == GeoCell.Adjacency.EDGE).toList();
    }

    /**
     * A REPORT is "corroborated" once someone INDEPENDENT of its reporter (not linked, Independence) counts as a
     * supporter of the same signal. Credited once per report (signal_contributions.credited).
     */
    private void creditCorroboratedReports(LocalDateTime now) {
        List<PlaceSignal> candidates = signals.findAllById(contributions.signalIdsWithUncreditedReports(ContributionKind.REPORT))
                .stream().filter(PlaceSignal::isUnmoderated).toList();
        if (candidates.isEmpty()) return;
        Map<Long, List<SignalContribution>> bySignal = contributionsOf(candidates);
        Snapshot snapshot = trust.snapshot(bySignal.values().stream().flatMap(List::stream).toList(), now);
        for (PlaceSignal signal : candidates) {
            List<SignalContribution> all = bySignal.getOrDefault(signal.getId(), List.of());
            Linkage support = snapshot.supportOn(signal.getId(), all); // Phase 9C.1: a moderator's dismissal counts here too
            List<SignalContribution> counted = all.stream()
                    .filter(c -> c.getKind().supports() && !c.getCountsFrom().isAfter(now)
                            && c.getCountsFrom().isAfter(now.minus(signal.getCategory().lifetime()))
                            && snapshot.weight(c.getUserId()) > 0)
                    .toList();
            for (SignalContribution report : counted) {
                if (report.getKind() != ContributionKind.REPORT || report.isCredited()) continue;
                boolean independentSupport = counted.stream().anyMatch(other -> !other.getUserId().equals(report.getUserId())
                        && !support.linked(report.getUserId(), other.getUserId()));
                if (!independentSupport) continue;
                report.markCredited();
                trust.standingFor(report.getUserId(), now).addCorroborated();
            }
        }
    }

    /**
     * Phase 8C: remembers (on the signal row only) that independent people confirmed it (MODERATE or HIGH) while it
     * was visible. Checked every run, so a signal confirmed for at least one run counts; a shorter one is missed
     * (conservative).
     */
    private void markReachedConfirmed(LocalDateTime now) {
        List<PlaceSignal> candidates = signals.findByModerationAndReachedConfirmedFalse(ModerationState.NONE);
        if (candidates.isEmpty()) return;
        Map<Long, List<SignalContribution>> bySignal = contributionsOf(candidates);
        Snapshot snapshot = trust.snapshot(bySignal.values().stream().flatMap(List::stream).toList(), now);
        for (PlaceSignal signal : candidates) {
            Assessment assessment = snapshot.assess(signal, bySignal.getOrDefault(signal.getId(), List.of()), now);
            if (assessment.visible() && assessment.confidence() != Confidence.LOW) {
                signal.markReachedConfirmed();
                outcomes.signalConfirmed(signal.getCategory()); // Phase 9B: once per signal, after commit
            }
        }
    }

    /**
     * Phase 9B: these signal rows are about to be deleted; counts how each ended (and whether independent people had
     * confirmed it), and any review case still open about them as unreviewed. In the caller's transaction, so a
     * rolled-back deletion counts nothing.
     */
    private void ending(List<PlaceSignal> ended, SignalEnd end) {
        if (ended.isEmpty()) return;
        ended.forEach(s -> outcomes.signalEnded(s.getCategory(), end, s.isReachedConfirmed()));
        moderation.countUnreviewed(ended.stream().map(PlaceSignal::getId).toList());
    }

    /**
     * Phase 8C: a signal whose lifetime ended by itself (not retired, not removed, not under review now) after
     * independent people had confirmed it is one pattern EPISODE for its area. Nothing about the signal is kept.
     */
    private void endedNaturally(PlaceSignal signal) {
        if (signal.isReachedConfirmed() && signal.isUnmoderated() && !moderation.isUnderReview(signal.getId())) {
            patterns.episode(signal.getAreaCell(), signal.getCategory().group());
        }
    }

    /** A retired signal: each REPORT that no independent person ever backed counts as "refuted" for its reporter. */
    private void recordUnsupportedReports(PlaceSignal signal, List<SignalContribution> list, Snapshot snapshot,
                                          LocalDateTime now) {
        Linkage support = snapshot.supportOn(signal.getId(), list);
        for (SignalContribution c : list) {
            if (c.getKind() != ContributionKind.REPORT || c.isCredited() || c.getCountsFrom().isAfter(now)) continue;
            boolean backed = list.stream().anyMatch(other -> other.getKind().supports()
                    && !other.getUserId().equals(c.getUserId()) && !other.getCountsFrom().isAfter(now)
                    && !support.linked(c.getUserId(), other.getUserId()));
            if (backed) continue;
            ContributorStanding standing = trust.standingFor(c.getUserId(), now);
            standing.addRefuted();
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    /** A signal everyone can currently see, with its contributions and assessment; anything else is 404. */
    private record Visible(PlaceSignal signal, List<SignalContribution> contributions, Assessment assessment) {
    }

    private Visible visibleSignal(String publicId, LocalDateTime now) {
        PlaceSignal signal = signals.findByPublicId(publicId).orElseThrow(CommunityService::notFound);
        if (!signal.isUnmoderated()) throw notFound(); // hidden for review: does not exist for anyone else
        List<SignalContribution> existing = contributions.findBySignalId(signal.getId());
        Assessment assessment = trust.snapshot(existing, now).assess(signal, existing, now);
        if (!assessment.visible()) throw notFound(); // pending, retired or expired signals do not exist for anyone
        return new Visible(signal, existing, assessment);
    }

    /**
     * Nearness is checked at the precision the signal is SHOWN at. Checking an area signal against its hidden block
     * cell would let someone find that cell by trying different "near" values.
     */
    private static void requireNear(Visible visible, String nearCell) {
        boolean block = visible.assessment.precision() == Precision.BLOCK;
        String target = block ? visible.signal.getCell() : visible.signal.getAreaCell();
        String mine = block ? nearCell : nearCell.substring(0, GeoCell.AREA_PRECISION);
        if (!GeoCell.neighbourhood(mine).contains(target)) {
            throw new ConflictException("You can only answer signals near where you are");
        }
    }

    private void requireNotPaused(long userId, LocalDateTime now) {
        if (trust.isRestricted(userId, now)) throw new ForbiddenException(PAUSED);
    }

    /** Phase 7: scripted bursts (reports + answers + flags) are refused, whatever the daily budget left. */
    private void burst(long userId) {
        if (!writeBursts.tryAcquire(String.valueOf(userId))) {
            throw new TooManyAttemptsException("You are sending a lot very quickly. Please wait a few minutes.");
        }
    }

    private void deleteSignal(PlaceSignal signal) {
        contributions.deleteBySignalIds(List.of(signal.getId()));
        signals.delete(signal);
        signals.flush();
    }

    private void enforceLimits(long userId, ContributionKind kind, String areaCell, LocalDateTime now) {
        LocalDateTime since = now.minus(DAY);
        if (kind == ContributionKind.REPORT) {
            if (contributions.countByUserIdAndKindAndCountsFromAfter(userId, kind, since) >= properties.maxReportsPerDay()) {
                throw new TooManyAttemptsException("You have reached today's limit for reports. Thank you for helping; "
                        + "please try again tomorrow.");
            }
            if (contributions.countInArea(userId, kind, areaCell, since) >= properties.maxReportsPerAreaPerDay()) {
                throw new TooManyAttemptsException("You have already reported several things in this area today.");
            }
        }
        if (contributions.countByUserIdAndCountsFromAfter(userId, since) >= properties.maxContributionsPerDay()) {
            throw new TooManyAttemptsException("You have reached today's limit. Please try again tomorrow.");
        }
    }

    /** When a new contribution starts counting: after the account's probation, plus a random publication delay. */
    private LocalDateTime countsFrom(long userId, LocalDateTime now) {
        LocalDateTime start = probationEnd(userId);
        if (start.isBefore(now)) start = now;
        long spanMillis = properties.publishDelayMax().minus(properties.publishDelayMin()).toMillis();
        long jitter = spanMillis > 0 ? random.nextLong(spanMillis + 1) : 0;
        return start.plus(properties.publishDelayMin()).plus(Duration.ofMillis(jitter)).truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDateTime probationEnd(long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        return user.getCreatedAt().plus(properties.newAccountProbation());
    }

    /** An upper bound, so the phone can say "appears within about N minutes". Says nothing about anyone else. */
    private Accepted accepted(long userId, LocalDateTime now) {
        LocalDateTime start = probationEnd(userId);
        if (start.isBefore(now)) start = now;
        long minutes = (long) Math.ceil(Duration.between(now, start.plus(properties.publishDelayMax())).toSeconds() / 60.0);
        return new Accepted("RECORDED", (int) Math.min(Integer.MAX_VALUE, Math.max(0, minutes)));
    }

    private Map<Long, List<SignalContribution>> contributionsOf(Collection<PlaceSignal> list) {
        if (list.isEmpty()) return Map.of();
        Set<Long> ids = list.stream().map(PlaceSignal::getId).collect(Collectors.toSet());
        return contributions.findBySignalIdIn(ids).stream()
                .collect(Collectors.groupingBy(SignalContribution::getSignalId));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Signal not found");
    }
}
