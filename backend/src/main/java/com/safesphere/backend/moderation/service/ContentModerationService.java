package com.safesphere.backend.moderation.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.common.independence.Independence;
import com.safesphere.backend.config.ModerationProperties;
import com.safesphere.backend.moderation.assessment.AssessmentService;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AppealAccepted;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AppealView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AuthorView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseDecision;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseDetail;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseSummary;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ContentView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ContextMessage;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ConversationContext;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.DecisionView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.Participant;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.PersonView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.PhotoView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.RestrictionView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.StandingView;
import com.safesphere.backend.moderation.dto.ModerationDtos.AccountAge;
import com.safesphere.backend.moderation.entity.ContentCase;
import com.safesphere.backend.moderation.entity.ContentReport;
import com.safesphere.backend.moderation.entity.ModerationAction;
import com.safesphere.backend.moderation.entity.ModerationAppeal;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionReason;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.ContentOutcome;
import com.safesphere.backend.moderation.entity.ModerationEnums.TargetKind;
import com.safesphere.backend.moderation.entity.SocialStrike;
import com.safesphere.backend.moderation.repository.ContentCaseRepository;
import com.safesphere.backend.moderation.repository.ContentReportRepository;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.moderation.repository.SocialStrikeRepository;
import com.safesphere.backend.social.entity.DailyQuestion;
import com.safesphere.backend.social.entity.Conversation;
import com.safesphere.backend.social.entity.Message;
import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import com.safesphere.backend.social.entity.ResponseEnums.MessageKind;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentMedia;
import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
import com.safesphere.backend.social.entity.ContentModeration.ModerationSource;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.entity.SocialStanding;
import com.safesphere.backend.social.entity.SocialStanding.Restriction;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.repository.DailyQuestionRepository;
import com.safesphere.backend.social.repository.MomentMediaRepository;
import com.safesphere.backend.social.repository.ConversationRepository;
import com.safesphere.backend.social.repository.MessageRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.service.MembershipGate;
import com.safesphere.backend.social.repository.ProfileRepository;
import com.safesphere.backend.social.repository.SocialStandingRepository;
import com.safesphere.backend.social.service.Relationships;
import com.safesphere.backend.social.service.SocialAccess;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moderation of moments (Phase N4; posts and perspectives before) and of people. Phase 13D: (docs/PHASE13D_PLAN.md M1, M7-M12, M17-M20). The Phase 7
 * principles, kept:
 *   - content can be hidden automatically only reversibly (ReportService: a collapse); HOLD and REMOVE are a human's;
 *   - PEOPLE are restricted only by a human, with a reason code, in the append-only audit log, appealable to ANOTHER
 *     moderator (the shared appeals table);
 *   - moderators see the content and case pseudonyms, and cannot act on a case they are part of;
 *   - a pause stops social writes only (SocialAccess); SOS, Safe Walk, the Safety Circle and the vault never read it;
 *   - the role is re-read from the database on every call (ModerationService.requireModerator, shared request limit).
 */
@Service
public class ContentModerationService {

    /** Codes a moderator may give for a violation (also the strike and pause reasons). */
    static final Set<ActionReason> VIOLATIONS = EnumSet.of(ActionReason.HARASSMENT, ActionReason.SEXUAL_HARASSMENT,
            ActionReason.HATE, ActionReason.THREAT, ActionReason.DOXXING, ActionReason.SPAM,
            ActionReason.NUDITY_OR_VIOLENCE, ActionReason.IMPERSONATION, ActionReason.OTHER);

    static final List<ActionType> CONTENT_ACTIONS = List.of(ActionType.CONTENT_KEEP, ActionType.CONTENT_HOLD,
            ActionType.CONTENT_COLLAPSE, ActionType.CONTENT_REMOVE);
    static final List<ActionType> PERSON_STEPS = List.of(ActionType.PERSON_WARN, ActionType.PERSON_PAUSE_COMMENTS,
            ActionType.PERSON_PAUSE_POSTING, ActionType.PERSON_SUSPEND);

    /** Decisions that confirm a violation: the first of them on a case records the case's one strike (M10). */
    static final Set<ActionType> STRIKING = EnumSet.of(ActionType.CONTENT_REMOVE, ActionType.PERSON_WARN,
            ActionType.PERSON_PAUSE_COMMENTS, ActionType.PERSON_PAUSE_POSTING, ActionType.PERSON_SUSPEND);

    /** What the person concerned sees in their standing (never a KEEP: they were not told anything was reported). */
    static final Set<ActionType> SHOWN_TO_PERSON = EnumSet.of(ActionType.CONTENT_HOLD, ActionType.CONTENT_COLLAPSE,
            ActionType.CONTENT_REMOVE, ActionType.PERSON_WARN, ActionType.PERSON_PAUSE_COMMENTS,
            ActionType.PERSON_PAUSE_POSTING, ActionType.PERSON_SUSPEND, ActionType.PERSON_REMOVE_PHOTO);

    static final Set<ActionType> APPEALABLE = EnumSet.of(ActionType.CONTENT_COLLAPSE, ActionType.CONTENT_REMOVE,
            ActionType.PERSON_PAUSE_COMMENTS, ActionType.PERSON_PAUSE_POSTING, ActionType.PERSON_SUSPEND);

    static final Duration STANDING_WINDOW = Duration.ofDays(90);
    static final int CONTEXT_MAX = 500;

    /** Phase N5 (N-97): earlier messages a moderator sees around a reported one. */
    static final int EARLIER_MESSAGES = 10;

    private final ModerationService moderation;
    private final ReportService reportService;
    private final ContentCaseRepository cases;
    private final ContentReportRepository reports;
    private final SocialStrikeRepository strikes;
    private final ModerationActionRepository actions;
    private final ModerationAppealRepository appeals;
    private final MomentRepository moments;
    private final MomentMediaRepository mediaRows;
    private final DailyQuestionRepository questions;
    private final MediaStorage mediaStorage;
    private final ProfileRepository profiles;
    private final SocialStandingRepository standings;
    private final SocialAccess access;
    private final UserRepository users;
    private final CaseRefs refs;
    private final ModerationProperties properties;
    private final AssessmentService assessmentService;
    private final com.safesphere.backend.social.service.ProfilePhotoService photos;
    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final Relationships relationships;
    private final com.safesphere.backend.social.service.PlanEvents planEvents;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final Clock clock;

    public ContentModerationService(ModerationService moderation, ReportService reportService,
                                    ContentCaseRepository cases, ContentReportRepository reports,
                                    SocialStrikeRepository strikes, ModerationActionRepository actions,
                                    ModerationAppealRepository appeals, MomentRepository moments,
                                    MomentMediaRepository mediaRows, DailyQuestionRepository questions,
                                    MediaStorage mediaStorage,
                                    ProfileRepository profiles, SocialStandingRepository standings,
                                    SocialAccess access, UserRepository users, CaseRefs refs,
                                    ModerationProperties properties, AssessmentService assessmentService,
                                    com.safesphere.backend.social.service.ProfilePhotoService photos,
                                    Clock clock,
                                    MessageRepository messages, ConversationRepository conversations,
                                    Relationships relationships,
                                    com.safesphere.backend.social.service.PlanEvents planEvents,
                                    org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.planEvents = planEvents;
        this.messages = messages;
        this.conversations = conversations;
        this.relationships = relationships;
        this.photos = photos;
        this.assessmentService = assessmentService;
        this.moderation = moderation;
        this.reportService = reportService;
        this.cases = cases;
        this.reports = reports;
        this.strikes = strikes;
        this.actions = actions;
        this.appeals = appeals;
        this.moments = moments;
        this.mediaRows = mediaRows;
        this.questions = questions;
        this.mediaStorage = mediaStorage;
        this.profiles = profiles;
        this.standings = standings;
        this.access = access;
        this.users = users;
        this.refs = refs;
        this.properties = properties;
        this.clock = clock;
    }

    // ---- Moderators: the queue and cases -----------------------------------------------------------------------------

    /** Open cases: highest priority (ReportPolicy) first, then oldest. Closed cases: newest first. */
    @Transactional(readOnly = true)
    public List<CaseSummary> listCases(long moderatorId, CaseStatus status) {
        moderation.requireModerator(moderatorId);
        LocalDateTime now = now();
        List<Loaded> loaded = new ArrayList<>();
        for (ContentCase c : cases.findByStatusOrderByOpenedAtAsc(status, PageRequest.of(0, 500))) {
            Loaded l = load(c);
            if (l != null) loaded.add(l);
        }
        List<CaseSummary> out = new ArrayList<>(loaded.stream().map(l -> summary(l, moderatorId, now)).toList());
        if (status == CaseStatus.OPEN) {
            out.sort(Comparator.comparingInt(CaseSummary::priority).reversed()
                    .thenComparing(CaseSummary::openedOn));
        } else {
            out.sort(Comparator.comparing(CaseSummary::openedOn).reversed());
        }
        return out.size() > properties.pageSize() ? List.copyOf(out.subList(0, properties.pageSize())) : out;
    }

    @Transactional(readOnly = true)
    public CaseDetail caseDetail(long moderatorId, String caseId) {
        moderation.requireModerator(moderatorId);
        Loaded l = requireCase(cases.findByPublicId(caseId).orElse(null));
        if (l.tookPart(moderatorId)) throw tookPart();
        return detail(l, moderatorId, now());
    }

    /** One decision. The case row is locked for the whole decision, so two moderators cannot both act. */
    @Transactional
    public CaseDetail act(long moderatorId, String caseId, ActionType action, ActionReason reason) {
        moderation.requireModerator(moderatorId);
        LocalDateTime now = now();
        Loaded l = requireCase(cases.findForUpdateByPublicId(caseId).orElse(null));
        if (l.tookPart(moderatorId)) throw tookPart();
        if (!possibleActions(l).contains(action)) {
            if (!l.c.isOpen()) throw new ConflictException("This case is already closed.");
            throw new InvalidRequestException("This action is not available on this case");
        }
        requireReason(action, reason);
        ModerationReason why = contentReason(reason);
        ContentCase c = l.c;
        // N7 (D38): did the author already see a notice on this content? (Then "Keep" is news to them; otherwise a Keep
        // would reveal that someone reported it, which the author is never told.)
        boolean authorSawANotice = l.contentState() != null && l.contentState() != ModerationState.NONE;
        Restriction pause = pauseOf(action);
        LocalDateTime until = pause == null ? null : now.plus(stepOf(action).length());
        // Phase 13E (E27): a decision about content the automatic check had collapsed or held is counted.
        ModerationAction entry = ModerationAction.social(moderatorId, action, reason, c.getPublicId(),
                c.getTargetKind(), l.subjectId, until, now);
        String decision = entry.getPublicId();
        boolean strike = STRIKING.contains(action)
                && !actions.existsByCaseRefAndActionIn(c.getPublicId(), STRIKING);

        switch (action) {
            case CONTENT_KEEP -> {
                setContentState(l, ModerationState.NONE, null, null);
                release(l.moment, now); // N-47: a moment held from the start begins its 24 hours here
                close(l, ContentOutcome.NO_VIOLATION, now);
            }
            case CONTENT_HOLD -> setContentState(l, ModerationState.HELD, why, decision);
            case CONTENT_COLLAPSE -> {
                setContentState(l, ModerationState.COLLAPSED, why, decision);
                close(l, ContentOutcome.ACTION_TAKEN, now);
            }
            case CONTENT_REMOVE -> {
                setContentState(l, ModerationState.REMOVED, why, decision);
                close(l, ContentOutcome.ACTION_TAKEN, now);
            }
            case PERSON_KEEP -> close(l, ContentOutcome.NO_VIOLATION, now);
            case PERSON_REMOVE_PHOTO -> photos.removeByModerator(l.subjectId, now);
            case PERSON_WARN, PERSON_PAUSE_COMMENTS, PERSON_PAUSE_POSTING, PERSON_SUSPEND -> {
                if (pause != null) applyPause(l.subjectId, pause, until, why, decision, now);
                if (c.getTargetKind() == TargetKind.PERSON) close(l, ContentOutcome.ACTION_TAKEN, now);
            }
            default -> throw new InvalidRequestException("This action is not available on this case");
        }
        actions.saveAndFlush(entry);
        if (strike) strikes.save(new SocialStrike(l.subjectId, why, StrikePolicy.weight(why), decision, now));
        cases.saveAndFlush(c);
        tellAuthor(l, action, authorSawANotice);
        return detail(load(c), moderatorId, now);
    }

    /** Phase 17E: the profile photo of the person a PERSON case is about (moderators only; shown after a tap). */
    @Transactional(readOnly = true)
    public byte[] personPhoto(long moderatorId, String caseId) {
        moderation.requireModerator(moderatorId);
        Loaded l = requireCase(cases.findByPublicId(caseId).orElse(null));
        if (l.tookPart(moderatorId)) throw tookPart();
        if (l.c.getTargetKind() != TargetKind.PERSON) throw new ResourceNotFoundException("No photo");
        return photos.photoOf(l.subjectId);
    }

    /** The photo of the moment a case is about (moderators only; the app shows it only after a tap, D15). */
    @Transactional(readOnly = true)
    public byte[] media(long moderatorId, String caseId) {
        moderation.requireModerator(moderatorId);
        Loaded l = requireCase(cases.findByPublicId(caseId).orElse(null));
        if (l.tookPart(moderatorId)) throw tookPart();
        List<MomentMedia> media = l.moment == null ? List.of() : mediaRows.findByMomentIdIn(List.of(l.moment.getId()));
        if (media.isEmpty()) throw new ResourceNotFoundException("Photo not found");
        try {
            return mediaStorage.read(media.get(0).getStorageKey());
        } catch (UncheckedIOException e) {
            throw new ResourceNotFoundException("Photo not found");
        }
    }

    // ---- Moderators: appeals about social decisions ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AppealView> listAppeals(long moderatorId) {
        moderation.requireModerator(moderatorId);
        List<AppealView> out = new ArrayList<>();
        for (ModerationAppeal appeal : appeals.findByStatusAndActionIn(AppealStatus.OPEN, APPEALABLE,
                PageRequest.of(0, properties.pageSize()))) {
            if (appeal.getUserId() == moderatorId) continue; // nobody reviews their own appeal
            ModerationAction decision = actions.findById(appeal.getActionId()).orElse(null);
            if (decision != null && !reportedIt(decision, moderatorId)) out.add(appealView(appeal, decision, moderatorId));
        }
        return out;
    }

    @Transactional
    public AppealView decideAppeal(long moderatorId, String appealId, AppealStatus outcome) {
        moderation.requireModerator(moderatorId);
        if (outcome != AppealStatus.UPHELD && outcome != AppealStatus.OVERTURNED) {
            throw new InvalidRequestException("Decide UPHELD or OVERTURNED");
        }
        LocalDateTime now = now();
        ModerationAppeal appeal = appeals.findByPublicId(appealId).orElseThrow(ContentModerationService::appealNotFound);
        if (appeal.getUserId() == moderatorId) throw appealNotFound();
        ModerationAction decision = actions.findById(appeal.getActionId())
                .filter(a -> APPEALABLE.contains(a.getAction()))
                .orElseThrow(ContentModerationService::appealNotFound);
        if (appeal.getStatus() != AppealStatus.OPEN) throw new ConflictException("This appeal is already decided.");
        if (decision.getActorId() == moderatorId) {
            throw new ForbiddenException("The moderator who made a decision cannot decide its appeal.");
        }
        if (reportedIt(decision, moderatorId)) throw tookPart();
        appeal.decide(outcome, moderatorId, now);
        if (outcome == AppealStatus.OVERTURNED) overturn(decision, now);
        actions.saveAndFlush(ModerationAction.socialAppeal(moderatorId,
                outcome == AppealStatus.OVERTURNED ? ActionType.APPEAL_OVERTURNED : ActionType.APPEAL_UPHELD,
                decision.getCaseRef(), decision.getTargetKind(), appeal.getUserId(), now));
        appeals.saveAndFlush(appeal);
        // N7 (D38): the person who appealed hears that it was decided (the details are in their decisions list).
        events.publishEvent(com.safesphere.backend.common.event.Tell.to(
                com.safesphere.backend.common.event.Tell.Kind.CONTENT_DECIDED, appeal.getUserId(), "decisions"));
        return appealView(appeal, decision, moderatorId);
    }

    /**
     * N7 (D38): a moderator's decision about a moment or a message reaches its author as "An update on what you shared"
     * (the phone shows no reason on the lock screen). Hold / collapse / remove always (the author sees the notice
     * anyway: no shadow states); Keep only when the author had already seen a notice. Person decisions show in the
     * standing banner and are not pushed. The subject says what to open: m-&lt;moment&gt; or c-&lt;conversation&gt;.
     */
    private void tellAuthor(Loaded l, ActionType action, boolean authorSawANotice) {
        if (l.subjectId == null) return;
        boolean content = action == ActionType.CONTENT_HOLD || action == ActionType.CONTENT_COLLAPSE
                || action == ActionType.CONTENT_REMOVE || (action == ActionType.CONTENT_KEEP && authorSawANotice);
        if (!content) return;
        String subject = null;
        if (l.message != null) {
            subject = conversations.findById(l.message.getConversationId()).map(cv -> "c-" + cv.getPublicId()).orElse(null);
        } else if (l.moment != null) {
            subject = "m-" + l.moment.getPublicId();
        }
        if (subject == null) return;
        events.publishEvent(com.safesphere.backend.common.event.Tell.to(
                com.safesphere.backend.common.event.Tell.Kind.CONTENT_DECIDED, l.subjectId, subject));
    }

    // ---- The person concerned ---------------------------------------------------------------------------------------

    /** The caller's pause (if any) and the decisions about them or their content in the last 90 days (M20). */
    @Transactional(readOnly = true)
    public StandingView standing(long userId) {
        access.requireAccount(userId); // Phase N3: shown even after a membership ended (no shadow decisions)
        LocalDateTime now = now();
        List<ModerationAction> decisions = actions.findAboutPersonSince(userId, SHOWN_TO_PERSON,
                now.minus(STANDING_WINDOW), PageRequest.of(0, 50));
        Map<Long, AppealStatus> appealed = new HashMap<>();
        for (ModerationAppeal a : appeals.findByActionIdIn(decisions.stream().map(ModerationAction::getId).toList())) {
            appealed.put(a.getActionId(), a.getStatus());
        }
        SocialStanding pause = access.pauseOf(userId).orElse(null);
        RestrictionView restriction = null;
        if (pause != null) {
            ModerationAction d = decisions.stream().filter(a -> a.getPublicId().equals(pause.getDecision()))
                    .findFirst().orElse(null);
            AppealStatus appeal = d == null ? null : appealed.get(d.getId());
            restriction = new RestrictionView(pause.getRestriction().name(),
                    pause.getRestrictedUntil().toInstant(ZoneOffset.UTC), pause.getReason().name(),
                    pause.getDecision(), appeal == null ? "NONE" : appeal.name(), d != null && appeal == null);
        }
        List<DecisionView> out = new ArrayList<>();
        for (ModerationAction d : decisions) {
            AppealStatus appeal = appealed.get(d.getId());
            out.add(new DecisionView(d.getPublicId(), d.getAction().name(), d.getReason().name(),
                    d.getTargetKind() == null ? null : d.getTargetKind().name(), targetIdOf(d),
                    Periods.of(d.getOccurredAt(), now), appeal == null ? "NONE" : appeal.name(),
                    appeal == null && canAppeal(d, now)));
        }
        return new StandingView(restriction, out);
    }

    /** "Please look again": one appeal per decision, by the person it is about, while it still applies. */
    @Transactional
    public AppealAccepted appeal(long userId, String decisionId) {
        access.requireAccount(userId);
        LocalDateTime now = now();
        ModerationAction decision = actions.findByPublicId(decisionId)
                .filter(a -> a.getSubjectUserId() != null && a.getSubjectUserId() == userId)
                .filter(a -> SHOWN_TO_PERSON.contains(a.getAction()))
                .orElseThrow(() -> new ResourceNotFoundException("Decision not found"));
        if (appeals.findByActionId(decision.getId()).isPresent()) {
            throw new ConflictException("You have already asked for a review of this decision.");
        }
        if (!canAppeal(decision, now)) throw new ConflictException("This decision can no longer be reviewed.");
        appeals.saveAndFlush(new ModerationAppeal(decision.getId(), userId, now));
        return new AppealAccepted(AppealStatus.OPEN.name());
    }

    // ---- Maintenance (ContentModerationJob, hourly) -------------------------------------------------------------------

    /**
     * Collapses content whose reports started to count since the last run (a new account's probation ended), deletes
     * what retention says (closed cases and their reports after the closed-case retention, strikes after the audit
     * retention, ended pauses) and anything left without its target. Returns how many rows changed.
     */
    @Transactional
    public int maintain() {
        LocalDateTime now = now();
        int changed = 0;
        changed += cases.deleteOrphans();
        changed += reports.deleteOrphans();
        for (ContentCase c : cases.findByStatusOrderByOpenedAtAsc(CaseStatus.OPEN, PageRequest.of(0, 500))) {
            if (reportService.evaluateCollapse(c, now)) changed++;
        }
        List<Long> old = cases.closedBefore(CaseStatus.CLOSED, now.minus(properties.closedCaseRetention()));
        if (!old.isEmpty()) {
            changed += reports.deleteByCaseIds(old);
            cases.deleteAllByIdInBatch(old);
            changed += old.size();
        }
        changed += strikes.deleteOlderThan(now.minus(properties.auditRetention()));
        changed += standings.deleteEnded(now);
        changed += moderation.maintain(); // N1: the audit log retention (it ran with the Nearby clean-up before)
        return changed;
    }

    // ---- Internals ----------------------------------------------------------------------------------------------------

    /** Everything about one case, loaded once per request; null when its target is gone. */
    private final class Loaded {
        final ContentCase c;
        final Moment moment;       // null for a PERSON or MESSAGE case
        final Message message;     // Phase N5: a MESSAGE case only
        final Long subjectId;      // the author or the person concerned
        final List<ContentReport> caseReports;

        Loaded(ContentCase c, Moment moment, Long subjectId, List<ContentReport> caseReports) {
            this(c, moment, null, subjectId, caseReports);
        }

        Loaded(ContentCase c, Moment moment, Message message, Long subjectId, List<ContentReport> caseReports) {
            this.c = c;
            this.moment = moment;
            this.message = message;
            this.subjectId = subjectId;
            this.caseReports = caseReports;
        }

        /** The person concerned (the author) or a reporter (also of "This is about me"): another moderator decides. */
        boolean tookPart(long userId) {
            return subjectId == userId || caseReports.stream().anyMatch(r -> r.getReporterId() == userId);
        }

        ModerationState contentState() {
            if (message != null) return message.getModeration();
            return moment == null ? null : moment.getModeration();
        }

        /** The moment's own moderation reason (null for a person or while NONE; never AWAITING_CHECK). */
        ModerationReason contentReason() {
            ModerationReason reason = rawReason();
            return reason == ModerationReason.AWAITING_CHECK ? null : reason;
        }

        /** Who set the moment's state (null for a person, or while NONE). */
        ModerationSource contentSource() {
            if (message != null) return message.getModerationSource();
            return moment == null ? null : moment.getModerationSource();
        }

        ModerationReason rawReason() {
            if (message != null) return message.getModerationReason();
            return moment == null ? null : moment.getModerationReason();
        }
    }

    private Loaded load(ContentCase c) {
        List<ContentReport> list = reports.findByCaseIdOrderByCreatedAtAscIdAsc(c.getId());
        switch (c.getTargetKind()) {
            case MOMENT -> {
                Moment moment = moments.findById(c.getMomentId()).orElse(null);
                return moment == null ? null : new Loaded(c, moment, moment.getAuthorId(), list);
            }
            case PERSON -> {
                return users.existsById(c.getPersonId()) ? new Loaded(c, null, c.getPersonId(), list) : null;
            }
            case MESSAGE -> {
                Message message = messages.findById(c.getMessageId()).orElse(null);
                return message == null ? null : new Loaded(c, null, message, message.getAuthorId(), list);
            }
            default -> {
                return null;
            }
        }
    }

    private Loaded requireCase(ContentCase c) {
        Loaded l = c == null ? null : load(c);
        if (l == null) throw new ResourceNotFoundException("Case not found");
        return l;
    }

    private CaseSummary summary(Loaded l, long moderatorId, LocalDateTime now) {
        ReportPolicy.Tally tally = reportService.tally(l.caseReports, now);
        Map<String, Integer> reasons = new LinkedHashMap<>();
        tally.reasons().forEach((k, v) -> reasons.put(k.name(), v));
        ModerationState state = l.contentState();
        // Once decided, a case still says what it was reported for (emulator finding: "A guideline" on closed cases).
        ModerationReason top = tally.topReason() != null ? tally.topReason() : l.caseReports.stream()
                .map(ContentReport::getReason)
                .max(Comparator.comparingInt(ReportPolicy::severity)).orElse(null);
        // Phase 13E (emulator finding): a case the automatic check or the author opened has no report: it says why the
        // content was collapsed or held (else the headline read "A guideline").
        if (top == null) top = l.contentReason();
        return new CaseSummary(l.c.getPublicId(), l.c.getTargetKind().name(), l.c.getStatus().name(),
                l.c.getOutcome() == null ? null : l.c.getOutcome().name(), l.c.getOpenedAt().toLocalDate(),
                top == null ? null : top.name(), reasons, tally.counting(),
                tally.independent(), state == null ? null : state.name(),
                QueuePolicy.priority(new QueuePolicy.Facts(l.c.getOrigin(), state, l.contentSource(),
                        l.rawReason(), ReportPolicy.priority(tally),
                        l.c.getOpenedAt()), l.c.isOpen() ? now : l.c.getOpenedAt()),
                l.tookPart(moderatorId), l.c.getOrigin().name(),
                l.contentSource() == null ? null : l.contentSource().name(),
                QueuePolicy.waitingHours(l.c.getOpenedAt(), l.c.isOpen() ? now : l.c.getClosedAt()));
    }

    private CaseDetail detail(Loaded l, long moderatorId, LocalDateTime now) {
        CaseSummary summary = summary(l, moderatorId, now);
        long caseId = l.c.getId();
        Set<Long> reporterIds = l.caseReports.stream().map(ContentReport::getReporterId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> groups = linkGroups(List.copyOf(reporterIds));
        Map<Long, User> accounts = new HashMap<>();
        Set<Long> everyone = new LinkedHashSet<>(reporterIds);
        everyone.add(l.subjectId);
        users.findAllById(everyone).forEach(u -> accounts.put(u.getId(), u));
        Set<Long> paused = new HashSet<>();
        for (SocialStanding s : standings.findAllById(reporterIds)) if (s.inForce(now)) paused.add(s.getUserId());

        List<Participant> participants = new ArrayList<>();
        participants.add(new Participant(refs.contentRef(caseId, l.subjectId),
                l.c.getTargetKind() == TargetKind.PERSON ? "SUBJECT" : "AUTHOR", null, null, false,
                ageOf(accounts.get(l.subjectId), now), null));
        List<Participant> reporters = new ArrayList<>();
        for (ContentReport r : l.caseReports) {
            boolean counting = !r.isResolved() && !r.getCountsFrom().isAfter(now) && !paused.contains(r.getReporterId());
            reporters.add(new Participant(refs.contentRef(caseId, r.getReporterId()), "REPORTER", r.getReason().name(),
                    r.getNote(), counting, ageOf(accounts.get(r.getReporterId()), now), groups.get(r.getReporterId())));
        }
        reporters.sort(Comparator.comparing(Participant::ref)); // an order that says nothing about who came first
        participants.addAll(reporters);

        List<StrikePolicy.Strike> own = strikes.findByUserId(l.subjectId).stream()
                .map(s -> new StrikePolicy.Strike(s.getWeight(), s.getCreatedAt())).toList();
        double points = StrikePolicy.points(own, now);
        ModerationReason top = summary.topReason() == null ? null : ModerationReason.valueOf(summary.topReason());
        SocialStanding pause = standings.findById(l.subjectId).filter(s -> s.inForce(now)).orElse(null);
        LocalDateTime established = access.memberOf(l.subjectId)
                .map(m -> MomentPolicy.establishedAt(m.verifiedAt())).filter(now::isBefore).orElse(null);
        AuthorView author = new AuthorView(refs.contentRef(caseId, l.subjectId), Math.round(points * 10) / 10.0,
                StrikePolicy.suggest(points, suggestFor(top)).name(),
                pause == null ? null : pause.getRestriction().name(),
                pause == null ? null : pause.getRestrictedUntil().toInstant(ZoneOffset.UTC),
                established == null ? null : established.toInstant(ZoneOffset.UTC));
        List<CaseDecision> decided = actions.findByCaseRefOrderByOccurredAtAscIdAsc(l.c.getPublicId()).stream()
                .map(a -> new CaseDecision(a.getAction().name(), a.getReason().name(),
                        a.getOccurredAt().toLocalDate(), a.getActorId() == moderatorId))
                .toList();
        return new CaseDetail(summary, content(l), participants, author,
                possibleActions(l).stream().map(Enum::name).toList(), decided, assessmentService.viewFor(l.c));
    }

    private ContentView content(Loaded l) {
        if (l.moment != null) {
            Moment m = l.moment;
            PhotoView photo = mediaRows.findByMomentIdIn(List.of(m.getId())).stream().findFirst()
                    .map(p -> new PhotoView(p.getWidth(), p.getHeight())).orElse(null);
            String question = m.getQuestionId() == null ? null
                    : questions.findById(m.getQuestionId()).map(DailyQuestion::getBody).orElse(null);
            return new ContentView(m.getKind().name(), m.getAudience().name(), m.getBody(),
                    m.getPlanAt() == null ? null : m.getPlanAt().toInstant(ZoneOffset.UTC), m.getPlanWhere(), question,
                    photo, null, m.getMoreText(), null);
        }
        if (l.message != null) return messageContent(l);
        Profile profile = profiles.findByUserId(l.subjectId).orElse(null);
        MembershipGate.Member member = access.memberOf(l.subjectId).orElse(null);
        PersonView person = profile == null ? null : new PersonView(profile.getDisplayName(), profile.getHandle(),
                profile.getBio(), member == null ? null : member.programme(),
                member == null ? null : member.graduationYear(), profile.getPhotoKey() != null);
        return new ContentView(null, null, null, null, null, null, null, person, null, null);
    }

    /**
     * Phase N5 (N-97): a reported message with up to 10 earlier messages of its conversation (what the people who
     * reported it could see: words nobody else was shown are left out), or a message the check held, alone, with
     * whether the two people of a private reply are connected. Writers appear only as this case's pseudonyms.
     */
    private ContentView messageContent(Loaded l) {
        Message target = l.message;
        Conversation conversation = conversations.findById(target.getConversationId()).orElse(null);
        long caseId = l.c.getId();
        List<ContextMessage> lines = new ArrayList<>();
        Boolean connected = null;
        if (!l.caseReports.isEmpty()) {
            List<Message> earlier = new ArrayList<>(messages.before(target.getConversationId(), target.getCreatedAt(),
                    target.getId(), PageRequest.of(0, EARLIER_MESSAGES * 3)));
            java.util.Collections.reverse(earlier);
            List<Message> shown = earlier.stream().filter(m -> m.getKind() == MessageKind.TEXT)
                    .filter(m -> m.getModeration().visibleToOthers()).toList();
            if (shown.size() > EARLIER_MESSAGES) shown = shown.subList(shown.size() - EARLIER_MESSAGES, shown.size());
            for (Message m : shown) lines.add(new ContextMessage(refs.contentRef(caseId, m.getAuthorId()), m.getBody(), false));
        } else if (conversation != null && conversation.getKind() == ConversationKind.REPLY) {
            Moment moment = moments.findById(conversation.getMomentId()).orElse(null);
            if (moment != null) {
                long other = target.getAuthorId() == moment.getAuthorId() ? conversation.getReplierId()
                        : moment.getAuthorId();
                connected = relationships.connected(target.getAuthorId(), other);
            }
        }
        lines.add(new ContextMessage(refs.contentRef(caseId, target.getAuthorId()), target.getBody(), true));
        ConversationContext context = new ConversationContext(conversation == null ? null
                : conversation.getKind().name(), lines, connected);
        return new ContentView("MESSAGE", null, target.getBody(), null, null, null, null, null, null, context);
    }

    /** What a moderator may do on this case now (the app shows only these; the server checks again). */
    private List<ActionType> possibleActions(Loaded l) {
        List<ActionType> out = new ArrayList<>();
        boolean content = l.c.getTargetKind() != TargetKind.PERSON;
        if (l.c.isOpen()) {
            if (content) {
                for (ActionType a : CONTENT_ACTIONS) {
                    if (a == ActionType.CONTENT_HOLD && l.contentState() == ModerationState.HELD) continue;
                    out.add(a);
                }
            } else {
                out.add(ActionType.PERSON_KEEP);
                // Phase 17E: a photo can be removed on its own; the case stays open for the decision about the person.
                if (photos.hasPhoto(l.subjectId)) out.add(ActionType.PERSON_REMOVE_PHOTO);
            }
            out.addAll(PERSON_STEPS);
        } else if (content && l.c.getOutcome() == ContentOutcome.ACTION_TAKEN) {
            out.addAll(PERSON_STEPS); // the author can still be warned or paused for what was confirmed
        }
        return out;
    }

    private static void requireReason(ActionType action, ActionReason reason) {
        boolean ok = switch (action) {
            case CONTENT_KEEP, PERSON_KEEP -> reason == ActionReason.NO_VIOLATION;
            case CONTENT_HOLD -> reason == ActionReason.NEEDS_REVIEW || VIOLATIONS.contains(reason);
            default -> VIOLATIONS.contains(reason);
        };
        if (!ok) throw new InvalidRequestException("This reason does not fit this action");
    }

    /** The content code of an action reason (the same names); null for NO_VIOLATION. */
    private static ModerationReason contentReason(ActionReason reason) {
        if (reason == ActionReason.NO_VIOLATION) return null;
        return ModerationReason.valueOf(reason.name());
    }

    private static Restriction pauseOf(ActionType action) {
        return switch (action) {
            case PERSON_PAUSE_COMMENTS -> Restriction.COMMENTS_PAUSED;
            case PERSON_PAUSE_POSTING -> Restriction.POSTING_PAUSED;
            case PERSON_SUSPEND -> Restriction.SUSPENDED;
            default -> null;
        };
    }

    private static StrikePolicy.Step stepOf(ActionType action) {
        return switch (action) {
            case PERSON_PAUSE_COMMENTS -> StrikePolicy.Step.PAUSE_COMMENTS;
            case PERSON_PAUSE_POSTING -> StrikePolicy.Step.PAUSE_POSTING;
            case PERSON_SUSPEND -> StrikePolicy.Step.SUSPEND;
            default -> StrikePolicy.Step.WARN;
        };
    }

    private void setContentState(Loaded l, ModerationState state, ModerationReason reason, String decision) {
        if (l.message != null) {
            l.message.moderate(state, reason, decision);
            messages.save(l.message);
            return;
        }
        if (l.moment == null) return;
        l.moment.moderate(state, reason, decision);
        moments.save(l.moment);
    }

    /** A moment nobody holds any more starts its 24 hours if it was never visible (N-47). Otherwise nothing. */
    private void release(Moment moment, LocalDateTime now) {
        if (moment == null) return;
        boolean first = moment.getVisibleAt() == null;
        moment.publish(now, MomentPolicy.LIFE);
        moments.save(moment);
        if (first) planEvents.becameVisible(moment, now);
    }

    /** Closes a case: its reports are resolved and its moment is no longer kept from the purge (N-47). */
    private void close(Loaded l, ContentOutcome outcome, LocalDateTime now) {
        l.c.close(outcome, now);
        for (ContentReport r : l.caseReports) r.resolve();
        reports.saveAll(l.caseReports);
        if (l.moment != null) {
            l.moment.setInReview(false);
            moments.save(l.moment);
        }
        if (l.message != null) {
            l.message.setInReview(false);
            messages.save(l.message);
        }
    }

    /** The reason a suggestion is based on: the codes that only mean "a person must look" suggest nothing. */
    private static ModerationReason suggestFor(ModerationReason top) {
        return top != null && top.reportable() ? top : null;
    }

    /** A pause never shortens a stronger or longer one already in force (the Phase 7 rule). */
    private void applyPause(long userId, Restriction level, LocalDateTime until, ModerationReason reason,
                            String decision, LocalDateTime now) {
        SocialStanding standing = standings.findById(userId).orElse(null);
        if (standing != null && standing.inForce(now) && standing.getRestriction().ordinal() >= level.ordinal()
                && !standing.getRestrictedUntil().isBefore(until)) {
            return;
        }
        if (standing == null) standing = new SocialStanding(userId);
        standing.pause(level, until, reason, decision, now);
        standings.save(standing);
    }

    /** An overturned decision: its content comes back, its pause ends (if still the one in force), its strike goes. */
    private void overturn(ModerationAction decision, LocalDateTime now) {
        String id = decision.getPublicId();
        switch (decision.getAction()) {
            case CONTENT_COLLAPSE, CONTENT_REMOVE -> {
                ContentCase c = cases.findByPublicId(decision.getCaseRef()).orElse(null);
                if (c != null && c.getMomentId() != null) {
                    moments.findById(c.getMomentId()).filter(m -> id.equals(m.getModerationDecision()))
                            .ifPresent(m -> {
                                m.moderate(ModerationState.NONE, null, null);
                                release(m, now);
                            });
                }
                if (c != null && c.getMessageId() != null) {
                    messages.findById(c.getMessageId()).filter(m -> id.equals(m.getModerationDecision()))
                            .ifPresent(m -> {
                                m.moderate(ModerationState.NONE, null, null);
                                messages.save(m);
                            });
                }
            }
            default -> standings.findById(decision.getSubjectUserId())
                    .filter(s -> id.equals(s.getDecision()))
                    .ifPresent(standings::delete);
        }
        strikes.deleteByDecision(id);
    }

    /**
     * A moderator who reported what a decision is about takes no part in its appeal either (conflict of interest).
     */
    private boolean reportedIt(ModerationAction decision, long moderatorId) {
        if (decision.getCaseRef() == null) return false;
        return cases.findByPublicId(decision.getCaseRef())
                .map(c -> reports.findByCaseIdOrderByCreatedAtAscIdAsc(c.getId()).stream()
                        .anyMatch(r -> r.getReporterId() == moderatorId))
                .orElse(false);
    }

    /** Whether the person may still ask for a review of [d] (no appeal yet is checked by the caller). */
    private boolean canAppeal(ModerationAction d, LocalDateTime now) {
        if (!APPEALABLE.contains(d.getAction())) return false;
        if (d.getAction() == ActionType.CONTENT_COLLAPSE || d.getAction() == ActionType.CONTENT_REMOVE) {
            ContentCase c = cases.findByPublicId(d.getCaseRef()).orElse(null);
            if (c == null) return false;
            if (c.getMessageId() != null) {
                return messages.findById(c.getMessageId())
                        .map(m -> d.getPublicId().equals(m.getModerationDecision())).orElse(false);
            }
            return c.getMomentId() != null && moments.findById(c.getMomentId())
                    .map(m -> d.getPublicId().equals(m.getModerationDecision())).orElse(false);
        }
        return standings.findById(d.getSubjectUserId())
                .map(s -> s.inForce(now) && d.getPublicId().equals(s.getDecision())).orElse(false);
    }

    /** The post or perspective a decision is about, while it exists (for the person's own list). */
    private String targetIdOf(ModerationAction d) {
        if (d.getCaseRef() == null) return null;
        ContentCase c = cases.findByPublicId(d.getCaseRef()).orElse(null);
        if (c == null) return null;
        if (c.getMessageId() != null) { // Phase N5: the conversation the message is in
            return messages.findById(c.getMessageId()).flatMap(m -> conversations.findById(m.getConversationId()))
                    .map(Conversation::getPublicId).orElse(null);
        }
        return c.getMomentId() == null ? null
                : moments.findById(c.getMomentId()).map(Moment::getPublicId).orElse(null);
    }

    private AppealView appealView(ModerationAppeal appeal, ModerationAction decision, long moderatorId) {
        return new AppealView(appeal.getPublicId(), appeal.getStatus().name(), appeal.getCreatedAt().toLocalDate(),
                decision.getAction().name(), decision.getReason().name(),
                decision.getTargetKind() == null ? null : decision.getTargetKind().name(), decision.getCaseRef(),
                decision.getActorId() == moderatorId);
    }

    /** Letters for reporters who keep reporting the same things (display only; Independence linkage). */
    private Map<Long, String> linkGroups(List<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (ids.size() < 2) return result;
        Independence.Linkage linkage = reportService.linkage(Set.copyOf(ids));
        Map<Long, Long> parent = new HashMap<>();
        ids.forEach(id -> parent.put(id, id));
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (linkage.linked(ids.get(i), ids.get(j))) parent.put(root(parent, ids.get(i)), root(parent, ids.get(j)));
            }
        }
        Map<Long, List<Long>> members = new LinkedHashMap<>();
        for (Long id : ids) members.computeIfAbsent(root(parent, id), k -> new ArrayList<>()).add(id);
        char letter = 'A';
        for (List<Long> group : members.values()) {
            if (group.size() < 2) continue;
            String label = String.valueOf(letter++);
            group.forEach(id -> result.put(id, label));
        }
        return result;
    }

    private static Long root(Map<Long, Long> parent, Long id) {
        Long current = id;
        while (!parent.get(current).equals(current)) current = parent.get(current);
        return current;
    }

    private static AccountAge ageOf(User user, LocalDateTime now) {
        if (user == null || user.getCreatedAt() == null) return AccountAge.NEW;
        Duration age = Duration.between(user.getCreatedAt(), now);
        if (age.compareTo(Duration.ofDays(1)) < 0) return AccountAge.NEW;
        if (age.compareTo(Duration.ofDays(30)) < 0) return AccountAge.RECENT;
        return AccountAge.ESTABLISHED;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static ResourceNotFoundException appealNotFound() {
        return new ResourceNotFoundException("Appeal not found");
    }

    private static ForbiddenException tookPart() {
        return new ForbiddenException("You are part of this case; another moderator must review it.");
    }
}
