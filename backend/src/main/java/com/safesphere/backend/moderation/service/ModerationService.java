package com.safesphere.backend.moderation.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.community.entity.ContributionKind;
import com.safesphere.backend.community.entity.ContributorStanding;
import com.safesphere.backend.community.entity.FlagReason;
import com.safesphere.backend.community.entity.ModerationState;
import com.safesphere.backend.community.entity.PlaceSignal;
import com.safesphere.backend.community.entity.SignalContribution;
import com.safesphere.backend.community.entity.SignalFlag;
import com.safesphere.backend.community.repository.PlaceSignalRepository;
import com.safesphere.backend.community.repository.SignalContributionRepository;
import com.safesphere.backend.community.repository.SignalFlagRepository;
import com.safesphere.backend.community.service.FlagPolicy;
import com.safesphere.backend.community.service.FlagPolicy.FlagVote;
import com.safesphere.backend.community.service.SignalPolicy.Assessment;
import com.safesphere.backend.community.service.SignalPolicy.Confidence;
import com.safesphere.backend.community.service.SignalPolicy.Reason;
import com.safesphere.backend.community.service.TrustPolicy.Standing;
import com.safesphere.backend.community.service.TrustPolicy.Tier;
import com.safesphere.backend.community.service.TrustService;
import com.safesphere.backend.community.service.TrustService.Snapshot;
import com.safesphere.backend.config.ModerationProperties;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Report;
import com.safesphere.backend.evaluation.service.CaseTrigger;
import com.safesphere.backend.evaluation.service.EvaluationService;
import com.safesphere.backend.evaluation.service.OutcomeRecorder;
import com.safesphere.backend.moderation.dto.ModerationDtos.Access;
import com.safesphere.backend.moderation.dto.ModerationDtos.AccountAge;
import com.safesphere.backend.moderation.dto.ModerationDtos.ActionRequest;
import com.safesphere.backend.moderation.dto.ModerationDtos.AppealAccepted;
import com.safesphere.backend.moderation.dto.ModerationDtos.AppealView;
import com.safesphere.backend.moderation.dto.ModerationDtos.AuditEntry;
import com.safesphere.backend.moderation.dto.ModerationDtos.CaseDetail;
import com.safesphere.backend.moderation.dto.ModerationDtos.CaseSummary;
import com.safesphere.backend.moderation.dto.ModerationDtos.Coordination;
import com.safesphere.backend.moderation.dto.ModerationDtos.CoordinationReason;
import com.safesphere.backend.moderation.dto.ModerationDtos.GroupPlaces;
import com.safesphere.backend.moderation.dto.ModerationDtos.GroupSize;
import com.safesphere.backend.community.service.CoordinationPolicy.Group;
import com.safesphere.backend.community.service.CoordinationPolicy.Rule;
import com.safesphere.backend.moderation.dto.ModerationDtos.MyStanding;
import com.safesphere.backend.moderation.dto.ModerationDtos.Participant;
import com.safesphere.backend.moderation.dto.ModerationDtos.RoleChange;
import com.safesphere.backend.moderation.entity.ModerationAction;
import com.safesphere.backend.moderation.entity.ModerationAppeal;
import com.safesphere.backend.moderation.entity.ModerationCase;
import com.safesphere.backend.intelligence.service.PatternService;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionReason;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOrigin;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOutcome;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.CoordinationRule;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.ModerationCaseRepository;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 7 moderation (docs/PHASE7.md §4). Principles enforced here, not in the app:
 *   - CONTENT can be hidden automatically (FlagPolicy quarantine, reversible); PEOPLE are only ever restricted by a
 *     human moderator, with a reason code, written to the append-only audit log, appealable to ANOTHER moderator;
 *   - a restriction pauses community contributions only; SOS, Safe Walk, Safety Circle and evidence are untouched;
 *   - moderators see places, categories, bands and case-scoped pseudonyms (CaseRefs) — never names, e-mails, ids or
 *     any other SafeSphere data — and cannot act on a case they took part in;
 *   - the role is re-read from the database on every call (defence in depth behind SecurityConfig).
 */
@Service
public class ModerationService {

    /** Content reasons a moderator may give for QUARANTINE, REMOVE or RESTRICT. */
    private static final Set<ActionReason> CONTENT_REASONS = Set.of(ActionReason.FALSE_INFORMATION,
            ActionReason.TARGETS_SOMEONE, ActionReason.SPAM_OR_COORDINATED);
    private static final Duration DEFAULT_RESTRICTION = Duration.ofDays(7);

    private final PlaceSignalRepository signals;
    private final SignalContributionRepository contributions;
    private final SignalFlagRepository flags;
    private final TrustService trust;
    private final ModerationCaseRepository cases;
    private final ModerationActionRepository actions;
    private final ModerationAppealRepository appeals;
    private final UserRepository users;
    private final CaseRefs refs;
    private final ModerationProperties properties;
    private final Clock clock;
    private final AttemptLimiter moderatorRequests;
    private final PatternService patterns;
    private final CoordinationCheck coordination;
    private final OutcomeRecorder outcomes;
    private final EvaluationService evaluation;

    public ModerationService(PlaceSignalRepository signals, SignalContributionRepository contributions,
                             SignalFlagRepository flags, TrustService trust, ModerationCaseRepository cases,
                             ModerationActionRepository actions, ModerationAppealRepository appeals,
                             UserRepository users, CaseRefs refs, ModerationProperties properties, Clock clock,
                             PatternService patterns, CoordinationCheck coordination, OutcomeRecorder outcomes,
                             EvaluationService evaluation) {
        this.patterns = patterns;
        this.coordination = coordination;
        this.outcomes = outcomes;
        this.evaluation = evaluation;
        this.signals = signals;
        this.contributions = contributions;
        this.flags = flags;
        this.trust = trust;
        this.cases = cases;
        this.actions = actions;
        this.appeals = appeals;
        this.users = users;
        this.refs = refs;
        this.properties = properties;
        this.clock = clock;
        this.moderatorRequests = new AttemptLimiter(properties.moderatorRequestsPerHour(), Duration.ofHours(1),
                10_000, clock);
    }

    // ---- Automatic part (no human): flags, re-reports, coordination ----------------------------------------------

    /**
     * After a new flag (or when flags of a new account start counting): opens a case if any flag counts and
     * quarantines the signal if FlagPolicy says so. Runs inside the caller's transaction.
     */
    @Transactional
    public void evaluateFlags(PlaceSignal signal, LocalDateTime now) {
        List<SignalContribution> list = contributions.findBySignalId(signal.getId());
        List<SignalFlag> flagRows = flags.findBySignalId(signal.getId());
        Snapshot snapshot = trust.snapshot(list, flagRows, now);
        Assessment assessment = snapshot.assess(signal, list, now);
        FlagPolicy.Outcome outcome = FlagPolicy.evaluate(flagVotes(flagRows, snapshot), assessment,
                snapshot.flagging(), now);
        if (outcome.openCase()) openCase(signal.getId(), CaseOrigin.FLAGS, null, now);
        if (outcome.quarantine() && signal.getModeration() == ModerationState.NONE) {
            signal.quarantine();
            signals.save(signal);
        }
    }

    /**
     * Periodic part of moderation (called by the clean-up job): re-checks flags that started counting, opens
     * REREPORTED / COORDINATION cases, ends tombstones, applies retention. Returns how many rows changed.
     */
    @Transactional
    public int maintain() {
        LocalDateTime now = now();
        int changed = 0;

        for (PlaceSignal signal : signals.findAllById(flags.signalIdsWithOpenFlags())) {
            if (signal.getModeration() != ModerationState.REMOVED) evaluateFlags(signal, now);
        }

        for (PlaceSignal tombstone : signals.findByModeration(ModerationState.REMOVED)) {
            List<SignalContribution> list = contributions.findBySignalId(tombstone.getId());
            if (!tombstone.getModerationUntil().isAfter(now)) {
                if (!list.isEmpty()) {             // the hold is over and people still report it: show it normally
                    tombstone.clearModeration();
                    changed++;
                }
                continue;                          // an empty finished tombstone is deleted by the community purge
            }
            if (list.isEmpty() || hasOpenCase(tombstone.getId())) continue;
            Assessment assessment = trust.snapshot(list, now).assess(tombstone, list, now);
            if (assessment.supporters() >= 2) {   // independent people say it again: a human looks again
                openCase(tombstone.getId(), CaseOrigin.REREPORTED, null, now);
                changed++;
            }
        }

        List<PlaceSignal> candidates = signals.findAllById(contributions.signalIdsWithSeveralContributions()).stream()
                .filter(PlaceSignal::isUnmoderated).toList();
        if (!candidates.isEmpty()) {
            Map<Long, List<SignalContribution>> bySignal = contributions.findBySignalIdIn(
                            candidates.stream().map(PlaceSignal::getId).toList()).stream()
                    .collect(Collectors.groupingBy(SignalContribution::getSignalId));
            Snapshot snapshot = trust.snapshot(bySignal.values().stream().flatMap(List::stream).toList(), now);
            for (PlaceSignal signal : candidates) {
                Assessment a = snapshot.assess(signal, bySignal.getOrDefault(signal.getId(), List.of()), now);
                // All the support comes from one group of accounts that keep acting together. Once a moderator has
                // dismissed such a case the pattern usually still holds, so without the last check every run would
                // open it again (docs/PHASE9_DESIGN.md §1.3 F9). Closed cases are kept 90 days; after that, one new look.
                if (a.supporters() == 1 && a.reasons().contains(Reason.LINKED_SUPPORT_COUNTED_ONCE)
                        && !hasOpenCase(signal.getId())
                        && !cases.existsBySignalIdAndOriginAndOutcome(signal.getId(), CaseOrigin.COORDINATION,
                                CaseOutcome.DISMISSED)) {
                    openCase(signal.getId(), CaseOrigin.COORDINATION, CoordinationRule.LINKED_GROUP, now);
                    changed++;
                }
            }
        }

        // Phase 9C coord-1: SHADOW only counts; ACTIVE opens review cases for a human (CoordinationCheck).
        changed += coordination.run(now).opened();

        changed += cases.deleteOrphans();
        changed += flags.deleteOrphans();
        changed += cases.deleteClosedBefore(CaseStatus.CLOSED, now.minus(properties.closedCaseRetention()));
        changed += actions.deleteOlderThan(now.minus(properties.auditRetention()));
        moderatorRequests.evictExpired();
        return changed;
    }

    /** True while a case about this signal is open (its contributors cannot withdraw to escape the review). */
    @Transactional(readOnly = true)
    public boolean isUnderReview(long signalId) {
        return hasOpenCase(signalId);
    }

    /**
     * Phase 9B: these signals are about to be deleted (expired, retired, withdrawn); every case still OPEN about them
     * ends unreviewed and is counted as such (moderator capacity). Runs inside the caller's transaction, so nothing is
     * counted if the deletion rolls back.
     */
    @Transactional
    public void countUnreviewed(Collection<Long> signalIds) {
        if (signalIds.isEmpty()) return;
        cases.findBySignalIdInAndStatus(signalIds, CaseStatus.OPEN).forEach(c -> outcomes.caseUnreviewed(trigger(c)));
    }

    /** Phase 9B: what asked for this case, as counted. */
    static CaseTrigger trigger(ModerationCase c) {
        return switch (c.getOrigin()) {
            case FLAGS -> CaseTrigger.FLAGS;
            case REREPORTED -> CaseTrigger.REREPORTED;
            case COORDINATION -> c.getCoordinationRule() == null ? CaseTrigger.COORDINATION
                    : CaseTrigger.valueOf(c.getCoordinationRule().name());
        };
    }

    /** Phase 9D: the signals among [signalIds] that a case is open about (the neighbour join leaves them alone). */
    @Transactional(readOnly = true)
    public Set<Long> underReview(Collection<Long> signalIds) {
        if (signalIds.isEmpty()) return Set.of();
        return Set.copyOf(cases.signalIdsWithStatus(signalIds, CaseStatus.OPEN));
    }

    /**
     * Phase 9C: whether a contributor may NOT withdraw now. People's complaints under review lock withdrawal (FLAGS,
     * REREPORTED, or flags on any open case), so a report cannot be pulled to escape the decision. A case the system
     * opened by itself (COORDINATION) does not: the refusal would tell a ring it had been detected (docs/PHASE9.md
     * S11), and withdrawing removes the fake support anyway.
     */
    @Transactional(readOnly = true)
    public boolean blocksWithdrawal(long signalId) {
        ModerationCase open = cases.findBySignalIdAndStatus(signalId, CaseStatus.OPEN).orElse(null);
        if (open == null) return false;
        if (open.getOrigin() != CaseOrigin.COORDINATION) return true;
        return flags.findBySignalId(signalId).stream().anyMatch(f -> !f.isResolved());
    }

    // ---- Moderators ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Access access(long userId) {
        UserRole role = users.findById(userId).filter(User::isEnabled).map(User::getRole).orElse(UserRole.USER);
        return new Access(role.canModerate(), role == UserRole.ADMIN);
    }

    @Transactional(readOnly = true)
    public List<CaseSummary> listCases(long moderatorId, CaseStatus status) {
        requireModerator(moderatorId);
        LocalDateTime now = now();
        List<CaseSummary> result = new ArrayList<>();
        List<Group> groups = null; // coord-1 findings, computed once per queue and only when there is a case
        for (ModerationCase c : cases.findByStatusOrderByOpenedAtAsc(status, PageRequest.of(0, properties.pageSize()))) {
            if (!signals.existsById(c.getSignalId())) continue;
            if (groups == null) groups = coordination.current(now);
            result.add(load(c, now, groups).summary(moderatorId));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public CaseDetail caseDetail(long moderatorId, String caseId) {
        requireModerator(moderatorId);
        LocalDateTime now = now();
        ModerationCase c = cases.findByPublicId(caseId).orElseThrow(ModerationService::caseNotFound);
        CaseView view = load(c, now, coordination.current(now));
        if (view.tookPart(moderatorId)) throw tookPart();
        return view.detail(moderatorId, view.coordination());
    }

    /** One moderator decision. The case row is locked for the whole decision, so two moderators cannot both act. */
    @Transactional
    public CaseDetail act(long moderatorId, String caseId, ActionRequest request) {
        requireModerator(moderatorId);
        LocalDateTime now = now();
        ModerationCase c = cases.findForUpdateByPublicId(caseId).orElseThrow(ModerationService::caseNotFound);
        if (!c.isOpen()) throw new ConflictException("This case is already closed.");
        CaseView view = load(c, now, coordination.current(now));
        if (view.tookPart(moderatorId)) throw tookPart();
        if (request.forWholeGroup() && request.action() != ActionType.RESTRICT) {
            throw new InvalidRequestException("Only a pause can cover the whole group");
        }
        PlaceSignal signal = view.signal;

        switch (request.action()) {
            case QUARANTINE -> {
                requireReason(request, ActionReason.NEEDS_REVIEW, ActionReason.FALSE_INFORMATION,
                        ActionReason.TARGETS_SOMEONE, ActionReason.SPAM_OR_COORDINATED);
                if (signal.getModeration() != ModerationState.NONE) {
                    throw new ConflictException("This signal is already hidden.");
                }
                signal.quarantine();
                audit(ModerationAction.onCase(moderatorId, ActionType.QUARANTINE, request.reason(), c.getPublicId(),
                        signal.getCategory(), now));
            }
            case DISMISS -> {
                requireReason(request, ActionReason.NO_VIOLATION);
                resolveFlags(view, now, false);
                signal.clearModeration();
                // Phase 9C.1: for a COORDINATION case this closed row is also the mark that lets the people counting
                // behind this signal now count separately on it (TrustService.Snapshot.supportOn). Nothing else stored.
                c.close(CaseOutcome.DISMISSED, now);
                outcomes.caseClosed(trigger(c), CaseOutcome.DISMISSED); // Phase 9B, after commit
                audit(ModerationAction.onCase(moderatorId, ActionType.DISMISS, request.reason(), c.getPublicId(),
                        signal.getCategory(), now));
            }
            case REMOVE -> {
                requireReason(request, ActionReason.FALSE_INFORMATION, ActionReason.TARGETS_SOMEONE,
                        ActionReason.SPAM_OR_COORDINATED, ActionReason.DUPLICATE);
                resolveFlags(view, now, true);
                for (SignalContribution contribution : view.contributions) {
                    if (contribution.getKind() != ContributionKind.REPORT) continue;
                    ContributorStanding standing = trust.standingFor(contribution.getUserId(), now);
                    switch (request.reason()) {
                        case FALSE_INFORMATION -> standing.addRefuted();
                        case TARGETS_SOMEONE, SPAM_OR_COORDINATED -> standing.addStrike();
                        default -> { }                // DUPLICATE: no consequence for anyone
                    }
                }
                // What was judged false or abusive is not kept: its contributions are deleted, the signal stays as a
                // hidden tombstone (no contributions) until the hold ends.
                contributions.deleteBySignalIds(List.of(signal.getId()));
                signal.remove(now.plus(properties.removalHold()));
                // Phase 8C: whatever this area "remembers" for the group may have been formed the same way: forget it.
                patterns.forget(signal.getAreaCell(), signal.getCategory().group());
                c.close(CaseOutcome.REMOVED, now);
                // Phase 9B, after commit. "Confirmed" = independent people had confirmed it (the Phase 8 mark), or do
                // so at the moment of removal: a confirmed signal removed as false is what the false-confirmation rate
                // counts.
                outcomes.caseClosed(trigger(c), CaseOutcome.REMOVED);
                outcomes.signalRemoved(signal.getCategory(),
                        signal.isReachedConfirmed() || view.assessment.confidence() != Confidence.LOW, request.reason());
                audit(ModerationAction.onCase(moderatorId, ActionType.REMOVE, request.reason(), c.getPublicId(),
                        signal.getCategory(), now));
            }
            case RESTRICT -> {
                requireReason(request, CONTENT_REASONS.toArray(ActionReason[]::new));
                Duration length = request.days() == null ? DEFAULT_RESTRICTION : Duration.ofDays(request.days());
                for (Long subject : restrictionSubjects(view, request)) {
                    ContributorStanding standing = trust.standingFor(subject, now);
                    LocalDateTime until = now.plus(length);
                    if (standing.getRestrictedUntil() != null && standing.getRestrictedUntil().isAfter(until)) {
                        until = standing.getRestrictedUntil(); // never shortens an existing, longer restriction
                    }
                    standing.restrictUntil(until);
                    standing.addStrike();
                    audit(ModerationAction.restriction(moderatorId, request.reason(), c.getPublicId(),
                            signal.getCategory(), subject, until, now));
                }
            }
            default -> throw new InvalidRequestException("This action is not available on a case");
        }
        signals.save(signal);
        cases.saveAndFlush(c);
        CaseView after = load(c, now, coordination.current(now));
        return after.detail(moderatorId, after.coordination());
    }

    /**
     * Who one RESTRICT decision pauses: the participant named by its case pseudonym, or (Phase 9C, [wholeGroup]) every
     * MEMBER of the coord-1 group behind this signal (Group.members) who is not paused already. The group is the one
     * computed for THIS decision, before anyone in it is paused: pausing members one by one makes their support stop
     * counting, so the pattern (and with it the list of the remaining members) can disappear from the case before the
     * last of them.
     */
    private List<Long> restrictionSubjects(CaseView view, ActionRequest request) {
        if (!request.forWholeGroup()) {
            if (request.contributorRef() == null) throw new InvalidRequestException("Choose the participant");
            Long subject = view.resolve(request.contributorRef());
            if (subject == null) throw new ResourceNotFoundException("Participant not found");
            return List.of(subject);
        }
        if (request.contributorRef() != null) {
            throw new InvalidRequestException("Choose one participant or the whole group, not both");
        }
        if (view.group == null) throw new ConflictException("No group stands behind this signal any more.");
        List<Long> subjects = view.group.members().stream().sorted()
                .filter(id -> view.snapshot.tier(id) != Tier.RESTRICTED).toList();
        if (subjects.isEmpty()) throw new ConflictException("Everyone in this group is already paused.");
        return subjects;
    }

    @Transactional(readOnly = true)
    public List<AppealView> listAppeals(long moderatorId) {
        requireModerator(moderatorId);
        LocalDateTime now = now();
        List<AppealView> result = new ArrayList<>();
        // Phase 13D: the appeals table is shared; this queue holds the community restrictions only (M11).
        for (ModerationAppeal appeal : appeals.findByStatusAndActionIn(AppealStatus.OPEN, List.of(ActionType.RESTRICT),
                PageRequest.of(0, properties.pageSize()))) {
            if (appeal.getUserId() == moderatorId) continue; // nobody reviews their own appeal
            result.add(appealView(appeal, moderatorId, now));
        }
        return result;
    }

    @Transactional
    public AppealView decideAppeal(long moderatorId, String appealId, AppealStatus decision) {
        requireModerator(moderatorId);
        LocalDateTime now = now();
        if (decision != AppealStatus.UPHELD && decision != AppealStatus.OVERTURNED) {
            throw new InvalidRequestException("Decide UPHELD or OVERTURNED");
        }
        ModerationAppeal appeal = appeals.findByPublicId(appealId).orElseThrow(ModerationService::appealNotFound);
        if (appeal.getUserId() == moderatorId) throw appealNotFound();
        if (appeal.getStatus() != AppealStatus.OPEN) throw new ConflictException("This appeal is already decided.");
        ModerationAction restriction = actions.findById(appeal.getActionId())
                .filter(a -> a.getAction() == ActionType.RESTRICT) // 13D appeals are decided by ContentModerationService
                .orElseThrow(ModerationService::appealNotFound);
        if (restriction.getActorId() == moderatorId) {
            throw new ForbiddenException("The moderator who made a decision cannot decide its appeal.");
        }
        appeal.decide(decision, moderatorId, now);
        if (decision == AppealStatus.OVERTURNED) {
            ContributorStanding standing = trust.standingFor(appeal.getUserId(), now);
            boolean newest = actions.findFirstBySubjectUserIdAndActionOrderByOccurredAtDescIdDesc(appeal.getUserId(),
                    ActionType.RESTRICT).map(a -> a.getId().equals(restriction.getId())).orElse(false);
            if (newest) standing.liftRestriction(); // a later, separate restriction stays in force
            standing.removeStrike();
        }
        audit(ModerationAction.aboutPerson(moderatorId,
                decision == AppealStatus.OVERTURNED ? ActionType.APPEAL_OVERTURNED : ActionType.APPEAL_UPHELD,
                ActionReason.APPEAL_REVIEWED, appeal.getUserId(), null, now));
        appeals.saveAndFlush(appeal);
        return appealView(appeal, moderatorId, now);
    }

    // ---- The person concerned (OWNER views) ----------------------------------------------------------------------

    /** The caller's own community standing: whether contributions are paused, why, until when, and their appeal. */
    @Transactional(readOnly = true)
    public MyStanding myStanding(long userId) {
        LocalDateTime now = now();
        if (!trust.isRestricted(userId, now)) return new MyStanding(false, null, null, null, false);
        ContributorStanding standing = trust.standingFor(userId, now);
        ModerationAction restriction = actions.findFirstBySubjectUserIdAndActionOrderByOccurredAtDescIdDesc(userId,
                ActionType.RESTRICT).orElse(null);
        ModerationAppeal appeal = restriction == null ? null : appeals.findByActionId(restriction.getId()).orElse(null);
        return new MyStanding(true, standing.getRestrictedUntil(), restriction == null ? null : restriction.getReason(),
                appeal == null ? null : appeal.getStatus(), restriction != null && appeal == null);
    }

    /** "Please look again": one appeal per restriction decision, while the restriction is in force. */
    @Transactional
    public AppealAccepted appeal(long userId) {
        LocalDateTime now = now();
        if (!trust.isRestricted(userId, now)) throw new ConflictException("There is no restriction to appeal.");
        ModerationAction restriction = actions.findFirstBySubjectUserIdAndActionOrderByOccurredAtDescIdDesc(userId,
                ActionType.RESTRICT).orElseThrow(() -> new ConflictException("There is no restriction to appeal."));
        if (appeals.findByActionId(restriction.getId()).isPresent()) {
            throw new ConflictException("You have already asked for a review of this decision.");
        }
        appeals.saveAndFlush(new ModerationAppeal(restriction.getId(), userId, now));
        return new AppealAccepted(AppealStatus.OPEN.name());
    }

    // ---- Administrators --------------------------------------------------------------------------------------------

    /** Appoints or removes a moderator. ADMIN accounts are managed only in the database (no escalation by API). */
    @Transactional
    public void changeRole(long adminId, RoleChange change) {
        requireAdmin(adminId);
        if (change.role() == UserRole.ADMIN) {
            throw new InvalidRequestException("Administrators are appointed in the database only");
        }
        User target = users.findByEmail(change.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (target.getId() == adminId) throw new ConflictException("You cannot change your own role.");
        if (target.getRole() == UserRole.ADMIN) throw new ConflictException("Administrators are managed in the database.");
        if (target.getRole() == change.role()) return;
        target.setRole(change.role());
        users.save(target);
        audit(ModerationAction.aboutPerson(adminId,
                change.role() == UserRole.MODERATOR ? ActionType.ROLE_GRANTED : ActionType.ROLE_REVOKED,
                ActionReason.ROLE_CHANGE, target.getId(), change.role(), now()));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> auditLog(long adminId, int page) {
        requireAdmin(adminId);
        if (page < 0) throw new InvalidRequestException("Page must be 0 or more");
        return actions.findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(page, properties.pageSize())).stream()
                .map(a -> new AuditEntry(a.getPublicId(), a.getActorId(), a.getAction(), a.getReason(), a.getCaseRef(),
                        a.getSignalCategory(), a.getSubjectUserId(), a.getRestrictedUntil(), a.getNewRole(),
                        a.getOccurredAt()))
                .toList();
    }

    /**
     * Phase 9B: the evaluation report (weekly, system-wide counters, rates, gate G1). ADMIN only, like the audit log.
     * The moderators count for gate G1 is a number of people, never who they are.
     */
    @Transactional(readOnly = true)
    public Report evaluationReport(long adminId, int weeks) {
        requireAdmin(adminId);
        long activeModerators = actions.countActorsSince(EnumSet.of(ActionType.QUARANTINE, ActionType.DISMISS,
                ActionType.REMOVE, ActionType.RESTRICT), now().minusDays(30));
        return evaluation.report(weeks, activeModerators);
    }

    // ---- Internals -------------------------------------------------------------------------------------------------

    /**
     * Everything about one case, loaded once per request. [group] (Phase 9C) is the coord-1 group the signal belongs
     * to right now (null if none), and [groupOnly] its MEMBERS (Group.members: people backing at least two of its
     * places) who are NOT behind this signal: they are listed to the moderator and can be paused from this case.
     */
    private final class CaseView {
        final ModerationCase moderationCase;
        final PlaceSignal signal;
        final List<SignalContribution> contributions;
        final List<SignalFlag> flagRows;
        final Snapshot snapshot;
        final Assessment assessment;
        final Group group;
        final List<Long> groupOnly;
        final LocalDateTime now;

        CaseView(ModerationCase moderationCase, PlaceSignal signal, List<SignalContribution> contributions,
                 List<SignalFlag> flagRows, Snapshot snapshot, Assessment assessment, Group group,
                 List<Long> groupOnly, LocalDateTime now) {
            this.moderationCase = moderationCase;
            this.signal = signal;
            this.contributions = contributions;
            this.flagRows = flagRows;
            this.snapshot = snapshot;
            this.assessment = assessment;
            this.group = group;
            this.groupOnly = groupOnly;
            this.now = now;
        }

        /**
         * Behind this signal, or (Phase 9C) anywhere in the group behind it (the whole core, members or not, to be
         * on the safe side): another moderator must review the case.
         */
        boolean tookPart(long userId) {
            return contributions.stream().anyMatch(c -> c.getUserId() == userId)
                    || flagRows.stream().anyMatch(f -> f.getUserId() == userId)
                    || (group != null && group.people().contains(userId));
        }

        /** Case ref → account, by recomputing refs for this case's own participants and group members only. */
        Long resolve(String ref) {
            for (Long userId : participantIds()) {
                if (refs.ref(moderationCase.getId(), userId).equals(ref)) return userId;
            }
            for (Long userId : groupOnly) {
                if (refs.ref(moderationCase.getId(), userId).equals(ref)) return userId;
            }
            return null;
        }

        List<Long> participantIds() {
            Set<Long> ids = new LinkedHashSet<>();
            contributions.forEach(c -> ids.add(c.getUserId()));
            flagRows.forEach(f -> ids.add(f.getUserId()));
            return List.copyOf(ids);
        }

        CaseSummary summary(long moderatorId) {
            Map<FlagReason, Integer> counts = new EnumMap<>(FlagReason.class);
            for (SignalFlag f : flagRows) counts.merge(f.getReason(), 1, Integer::sum);
            return new CaseSummary(moderationCase.getPublicId(), moderationCase.getOrigin(), moderationCase.getStatus(),
                    moderationCase.getOutcome(), moderationCase.getOpenedAt().toLocalDate(), signal.getCategory(),
                    signal.getCategory().group(), signal.getModeration(), counts, assessment.supporters(),
                    assessment.confidence(), tookPart(moderatorId));
        }

        CaseDetail detail(long moderatorId, Coordination coordination) {
            Map<Long, String> groups = linkGroups();
            List<Participant> participants = new ArrayList<>();
            for (SignalContribution c : contributions) {
                participants.add(new Participant(refs.ref(moderationCase.getId(), c.getUserId()), c.getKind(), null,
                        snapshot.tier(c.getUserId()), ageOf(c.getUserId()),
                        !c.getCountsFrom().isAfter(now) && snapshot.weight(c.getUserId()) > 0, groups.get(c.getUserId())));
            }
            for (SignalFlag f : flagRows) {
                participants.add(new Participant(refs.ref(moderationCase.getId(), f.getUserId()), null, f.getReason(),
                        snapshot.tier(f.getUserId()), ageOf(f.getUserId()),
                        !f.isResolved() && !f.getCountsFrom().isAfter(now) && snapshot.flagWeight(f.getUserId()) > 0,
                        groups.get(f.getUserId())));
            }
            // Phase 9C: the rest of the group, in an order that says nothing about the accounts (refs are random per case).
            List<Participant> members = new ArrayList<>();
            for (Long id : groupOnly) {
                members.add(new Participant(refs.ref(moderationCase.getId(), id), null, null, snapshot.tier(id),
                        ageOf(id), snapshot.weight(id) > 0, groups.get(id)));
            }
            members.sort(Comparator.comparing(Participant::ref));
            boolean publiclyVisible = assessment.visible() && signal.isUnmoderated();
            return new CaseDetail(summary(moderatorId), signal.getCell(), signal.getAreaCell(), assessment.precision(),
                    publiclyVisible, assessment.reasons(), assessment.disputers(), participants, tookPart(moderatorId),
                    coordination, List.copyOf(members));
        }

        /** Phase 9C: the coordination pattern behind this signal right now (Phase 7 R0 + coord-1), or null. */
        Coordination coordination() {
            EnumSet<CoordinationReason> reasons = EnumSet.noneOf(CoordinationReason.class);
            GroupSize size = null;
            GroupPlaces places = null;
            if (assessment.supporters() == 1 && assessment.reasons().contains(Reason.LINKED_SUPPORT_COUNTED_ONCE)) {
                reasons.add(CoordinationReason.LINKED_GROUP);
                size = GroupSize.of((int) contributions.stream().filter(c -> c.getKind().supports()
                        && !c.getCountsFrom().isAfter(now) && snapshot.weight(c.getUserId()) > 0).count());
            }
            if (group != null) {
                reasons.add(group.rule() == Rule.SUPPORT_CORE
                        ? CoordinationReason.SUPPORT_CORE : CoordinationReason.EXCLUSIVE_PAIR);
                size = GroupSize.of(group.members().size());
                places = GroupPlaces.of(group.signals().size());
            }
            return reasons.isEmpty() ? null : new Coordination(List.copyOf(reasons), size, places);
        }

        private AccountAge ageOf(long userId) {
            LocalDateTime created = snapshot.createdAt().get(userId);
            if (created == null) return AccountAge.NEW;
            Duration age = Duration.between(created, now);
            if (age.compareTo(Duration.ofDays(1)) < 0) return AccountAge.NEW;
            if (age.compareTo(Duration.ofDays(30)) < 0) return AccountAge.RECENT;
            return AccountAge.ESTABLISHED;
        }

        /** Letters for groups of participants (and group members) who keep acting together (display only). */
        private Map<Long, String> linkGroups() {
            List<Long> ids = new ArrayList<>(participantIds());
            ids.addAll(groupOnly);
            Map<Long, Long> parent = new HashMap<>();
            ids.forEach(id -> parent.put(id, id));
            for (int i = 0; i < ids.size(); i++) {
                for (int j = i + 1; j < ids.size(); j++) {
                    if (snapshot.linkedAnyhow(ids.get(i), ids.get(j))) {
                        parent.put(root(parent, ids.get(i)), root(parent, ids.get(j)));
                    }
                }
            }
            Map<Long, List<Long>> members = new LinkedHashMap<>();
            for (Long id : ids) members.computeIfAbsent(root(parent, id), k -> new ArrayList<>()).add(id);
            Map<Long, String> result = new HashMap<>();
            char letter = 'A';
            for (List<Long> group : members.values()) {
                if (group.size() < 2) continue;
                String label = String.valueOf(letter++);
                group.forEach(id -> result.put(id, label));
            }
            return result;
        }

        private Long root(Map<Long, Long> parent, Long id) {
            Long current = id;
            while (!parent.get(current).equals(current)) current = parent.get(current);
            return current;
        }
    }

    /** [groups] = the current coord-1 findings (CoordinationCheck.current), computed once per request. */
    private CaseView load(ModerationCase c, LocalDateTime now, List<Group> groups) {
        PlaceSignal signal = signals.findById(c.getSignalId()).orElseThrow(ModerationService::caseNotFound);
        List<SignalContribution> list = contributions.findBySignalId(signal.getId());
        List<SignalFlag> flagRows = flags.findBySignalId(signal.getId());
        Group group = groups.stream().filter(g -> g.contains(signal.getId())).findFirst().orElse(null);
        Set<Long> groupOnly = new LinkedHashSet<>(group == null ? Set.of() : group.members());
        list.forEach(x -> groupOnly.remove(x.getUserId()));
        flagRows.forEach(f -> groupOnly.remove(f.getUserId()));
        Snapshot snapshot = trust.snapshot(list, flagRows, groupOnly, now);
        return new CaseView(c, signal, list, flagRows, snapshot, snapshot.assess(signal, list, now), group,
                List.copyOf(groupOnly), now);
    }

    /** Marks open flags as decided and records, privately, whether each flagger was right. */
    private void resolveFlags(CaseView view, LocalDateTime now, boolean upheld) {
        for (SignalFlag flag : view.flagRows) {
            if (flag.isResolved()) continue;
            flag.resolve();
            ContributorStanding standing = trust.standingFor(flag.getUserId(), now);
            if (upheld) standing.addFlagUpheld(); else standing.addFlagRejected();
        }
        flags.saveAll(view.flagRows);
    }

    private AppealView appealView(ModerationAppeal appeal, long moderatorId, LocalDateTime now) {
        ModerationAction restriction = actions.findById(appeal.getActionId()).orElseThrow(ModerationService::appealNotFound);
        Snapshot snapshot = trust.snapshotOf(appeal.getUserId(), now);
        int strikes = snapshot.standingsById().getOrDefault(appeal.getUserId(), Standing.NEW).strikes();
        return new AppealView(appeal.getPublicId(), appeal.getStatus(), appeal.getCreatedAt().toLocalDate(),
                restriction.getReason(), restriction.getSignalCategory(),
                restriction.getRestrictedUntil() == null ? null : restriction.getRestrictedUntil().toLocalDate(),
                snapshot.tier(appeal.getUserId()), strikes, restriction.getActorId() == moderatorId);
    }

    private List<FlagVote> flagVotes(List<SignalFlag> rows, Snapshot snapshot) {
        return rows.stream().map(f -> new FlagVote(f.getReason(), f.getCountsFrom(), snapshot.flagWeight(f.getUserId()),
                f.getUserId(), f.isResolved())).toList();
    }

    private void openCase(long signalId, CaseOrigin origin, CoordinationRule rule, LocalDateTime now) {
        if (hasOpenCase(signalId)) return;
        ModerationCase opened = cases.saveAndFlush(new ModerationCase(signalId, origin, rule, now));
        outcomes.caseOpened(trigger(opened)); // Phase 9B, after commit
    }

    private boolean hasOpenCase(long signalId) {
        return cases.findBySignalIdAndStatus(signalId, CaseStatus.OPEN).isPresent();
    }

    private void audit(ModerationAction entry) {
        actions.saveAndFlush(entry);
    }

    /** Also used by ContentModerationService (13D): one role check and one per-moderator request limit for both. */
    User requireModerator(long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ForbiddenException("Moderators only"));
        if (!user.isEnabled() || !user.getRole().canModerate()) throw new ForbiddenException("Moderators only");
        if (!moderatorRequests.tryAcquire(String.valueOf(userId))) {
            throw new TooManyAttemptsException("Too many moderation requests. Please slow down.");
        }
        return user;
    }

    private void requireAdmin(long userId) {
        User user = requireModerator(userId);
        if (user.getRole() != UserRole.ADMIN) throw new ForbiddenException("Administrators only");
    }

    private static void requireReason(ActionRequest request, ActionReason... allowed) {
        for (ActionReason reason : allowed) if (reason == request.reason()) return;
        throw new InvalidRequestException("This reason does not fit this action");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static ResourceNotFoundException caseNotFound() {
        return new ResourceNotFoundException("Case not found");
    }

    private static ResourceNotFoundException appealNotFound() {
        return new ResourceNotFoundException("Appeal not found");
    }

    private static ForbiddenException tookPart() {
        return new ForbiddenException("You took part in this signal; another moderator must review it.");
    }
}
