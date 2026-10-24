package com.safesphere.backend.moderation.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.community.service.GeoCell;
import com.safesphere.backend.community.service.Independence;
import com.safesphere.backend.config.ModerationProperties;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AppealAccepted;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AppealView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AuthorView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseDecision;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseDetail;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.CaseSummary;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ContentView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.DecisionView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.MediaView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.Participant;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.PersonView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.PlaceView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.RestrictionView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.RevisionView;
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
import com.safesphere.backend.social.entity.Comment;
import com.safesphere.backend.social.entity.Place;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.entity.PostEnums.Precision;
import com.safesphere.backend.social.entity.PostMedia;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.entity.SocialStanding;
import com.safesphere.backend.social.entity.SocialStanding.Restriction;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.policy.ProfileLists;
import com.safesphere.backend.social.policy.TimeBands;
import com.safesphere.backend.social.repository.CommentRepository;
import com.safesphere.backend.social.repository.PlaceRepository;
import com.safesphere.backend.social.repository.PostMediaRepository;
import com.safesphere.backend.social.repository.PostRepository;
import com.safesphere.backend.social.repository.PostRevisionRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import com.safesphere.backend.social.repository.SocialStandingRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 13D: moderation of posts, perspectives and people (docs/PHASE13D_PLAN.md M1, M7-M12, M17-M20). The Phase 7
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
            ActionType.PERSON_PAUSE_POSTING, ActionType.PERSON_SUSPEND);

    static final Set<ActionType> APPEALABLE = EnumSet.of(ActionType.CONTENT_COLLAPSE, ActionType.CONTENT_REMOVE,
            ActionType.PERSON_PAUSE_COMMENTS, ActionType.PERSON_PAUSE_POSTING, ActionType.PERSON_SUSPEND);

    static final Duration STANDING_WINDOW = Duration.ofDays(90);
    static final int CONTEXT_MAX = 500;

    private final ModerationService moderation;
    private final ReportService reportService;
    private final ContentCaseRepository cases;
    private final ContentReportRepository reports;
    private final SocialStrikeRepository strikes;
    private final ModerationActionRepository actions;
    private final ModerationAppealRepository appeals;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final PostRevisionRepository revisions;
    private final PostMediaRepository mediaRows;
    private final MediaStorage mediaStorage;
    private final PlaceRepository places;
    private final ProfileRepository profiles;
    private final SocialStandingRepository standings;
    private final SocialAccess access;
    private final UserRepository users;
    private final CaseRefs refs;
    private final ModerationProperties properties;
    private final Clock clock;

    public ContentModerationService(ModerationService moderation, ReportService reportService,
                                    ContentCaseRepository cases, ContentReportRepository reports,
                                    SocialStrikeRepository strikes, ModerationActionRepository actions,
                                    ModerationAppealRepository appeals, PostRepository posts,
                                    CommentRepository comments, PostRevisionRepository revisions,
                                    PostMediaRepository mediaRows, MediaStorage mediaStorage, PlaceRepository places,
                                    ProfileRepository profiles, SocialStandingRepository standings,
                                    SocialAccess access, UserRepository users, CaseRefs refs,
                                    ModerationProperties properties, Clock clock) {
        this.moderation = moderation;
        this.reportService = reportService;
        this.cases = cases;
        this.reports = reports;
        this.strikes = strikes;
        this.actions = actions;
        this.appeals = appeals;
        this.posts = posts;
        this.comments = comments;
        this.revisions = revisions;
        this.mediaRows = mediaRows;
        this.mediaStorage = mediaStorage;
        this.places = places;
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
        Restriction pause = pauseOf(action);
        LocalDateTime until = pause == null ? null : now.plus(stepOf(action).length());
        ModerationAction entry = ModerationAction.social(moderatorId, action, reason, c.getPublicId(),
                c.getTargetKind(), l.subjectId, until, now);
        String decision = entry.getPublicId();
        boolean strike = STRIKING.contains(action)
                && !actions.existsByCaseRefAndActionIn(c.getPublicId(), STRIKING);

        switch (action) {
            case CONTENT_KEEP -> {
                setContentState(l, ModerationState.NONE, null, null);
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
            case PERSON_WARN, PERSON_PAUSE_COMMENTS, PERSON_PAUSE_POSTING, PERSON_SUSPEND -> {
                if (pause != null) applyPause(l.subjectId, pause, until, why, decision, now);
                if (c.getTargetKind() == TargetKind.PERSON) close(l, ContentOutcome.ACTION_TAKEN, now);
            }
            default -> throw new InvalidRequestException("This action is not available on this case");
        }
        actions.saveAndFlush(entry);
        if (strike) strikes.save(new SocialStrike(l.subjectId, why, StrikePolicy.weight(why), decision, now));
        cases.saveAndFlush(c);
        return detail(load(c), moderatorId, now);
    }

    /** A photo of the post a case is about (moderators only; the app shows it only after a tap, D15). */
    @Transactional(readOnly = true)
    public byte[] media(long moderatorId, String caseId, String mediaId) {
        moderation.requireModerator(moderatorId);
        Loaded l = requireCase(cases.findByPublicId(caseId).orElse(null));
        if (l.tookPart(moderatorId)) throw tookPart();
        PostMedia media = mediaId == null ? null : mediaRows.findByPublicId(mediaId).orElse(null);
        if (media == null || l.post == null || l.c.getTargetKind() != TargetKind.POST
                || !l.post.getId().equals(media.getPostId())) {
            throw new ResourceNotFoundException("Photo not found");
        }
        try {
            return mediaStorage.read(media.getStorageKey());
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
        if (outcome == AppealStatus.OVERTURNED) overturn(decision);
        actions.saveAndFlush(ModerationAction.socialAppeal(moderatorId,
                outcome == AppealStatus.OVERTURNED ? ActionType.APPEAL_OVERTURNED : ActionType.APPEAL_UPHELD,
                decision.getCaseRef(), decision.getTargetKind(), appeal.getUserId(), now));
        appeals.saveAndFlush(appeal);
        return appealView(appeal, decision, moderatorId);
    }

    // ---- Moderators: duplicate places (M17) --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PlaceView> places(long moderatorId, String area) {
        moderation.requireModerator(moderatorId);
        String cell = area == null ? "" : area.trim().toLowerCase(Locale.ROOT);
        if (!GeoCell.isValid(cell, GeoCell.AREA_PRECISION)) throw new InvalidRequestException("Invalid area");
        List<Place> found = places.findByAreaCellOrderByNameAsc(cell, PageRequest.of(0, 200));
        Map<Long, String> publicIds = found.stream().collect(Collectors.toMap(Place::getId, Place::getPublicId));
        List<PlaceView> out = new ArrayList<>();
        for (Place p : found) {
            String into = p.getMergedInto() == null ? null : publicIds.getOrDefault(p.getMergedInto(),
                    places.findById(p.getMergedInto()).map(Place::getPublicId).orElse(null));
            out.add(new PlaceView(p.getPublicId(), p.getName(), p.getCategory(), p.getCell(), into));
        }
        return out;
    }

    /** Merges duplicate [placeId] into [intoId]: same area only; its posts move; it stays as a pointer (one hop). */
    @Transactional
    public void merge(long moderatorId, String placeId, String intoId) {
        moderation.requireModerator(moderatorId);
        Place from = places.findByPublicId(placeId).orElseThrow(() -> new ResourceNotFoundException("Place not found"));
        Place into = places.findByPublicId(intoId).orElseThrow(() -> new ResourceNotFoundException("Place not found"));
        if (from.getId().equals(into.getId())) throw new InvalidRequestException("Choose two different places");
        if (!from.getAreaCell().equals(into.getAreaCell())) {
            throw new InvalidRequestException("Only places in the same area can be merged");
        }
        if (from.getMergedInto() != null || into.getMergedInto() != null) {
            throw new ConflictException("One of these places is already merged");
        }
        LocalDateTime now = now();
        posts.movePlace(from.getId(), into.getId());
        from.mergeInto(into.getId());
        places.saveAndFlush(from);
        actions.saveAndFlush(ModerationAction.social(moderatorId, ActionType.PLACE_MERGED, ActionReason.DUPLICATE,
                null, TargetKind.PLACE, null, null, now));
    }

    // ---- The person concerned ---------------------------------------------------------------------------------------

    /** The caller's pause (if any) and the decisions about them or their content in the last 90 days (M20). */
    @Transactional(readOnly = true)
    public StandingView standing(long userId) {
        access.requireMember(userId);
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
                    TimeBands.band(d.getOccurredAt(), now).name(), appeal == null ? "NONE" : appeal.name(),
                    appeal == null && canAppeal(d, now)));
        }
        return new StandingView(restriction, out);
    }

    /** "Please look again": one appeal per decision, by the person it is about, while it still applies. */
    @Transactional
    public AppealAccepted appeal(long userId, String decisionId) {
        access.requireMember(userId);
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
        return changed;
    }

    // ---- Internals ----------------------------------------------------------------------------------------------------

    /** Everything about one case, loaded once per request; null when its target is gone. */
    private final class Loaded {
        final ContentCase c;
        final Post post;           // the post (POST) or the perspective's post (COMMENT)
        final Comment comment;
        final Long subjectId;      // the author or the person concerned
        final List<ContentReport> caseReports;

        Loaded(ContentCase c, Post post, Comment comment, Long subjectId, List<ContentReport> caseReports) {
            this.c = c;
            this.post = post;
            this.comment = comment;
            this.subjectId = subjectId;
            this.caseReports = caseReports;
        }

        boolean tookPart(long userId) {
            return subjectId == userId || caseReports.stream().anyMatch(r -> r.getReporterId() == userId);
        }

        ModerationState contentState() {
            if (c.getTargetKind() == TargetKind.POST) return post.getModeration();
            if (c.getTargetKind() == TargetKind.COMMENT) return comment.getModeration();
            return null;
        }
    }

    private Loaded load(ContentCase c) {
        List<ContentReport> list = reports.findByCaseIdOrderByCreatedAtAscIdAsc(c.getId());
        switch (c.getTargetKind()) {
            case POST -> {
                Post post = posts.findById(c.getPostId()).orElse(null);
                return post == null ? null : new Loaded(c, post, null, post.getAuthorId(), list);
            }
            case COMMENT -> {
                Comment comment = comments.findById(c.getCommentId()).orElse(null);
                Post post = comment == null ? null : posts.findById(comment.getPostId()).orElse(null);
                return post == null ? null : new Loaded(c, post, comment, comment.getAuthorId(), list);
            }
            case PERSON -> {
                return users.existsById(c.getPersonId()) ? new Loaded(c, null, null, c.getPersonId(), list) : null;
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
        return new CaseSummary(l.c.getPublicId(), l.c.getTargetKind().name(), l.c.getStatus().name(),
                l.c.getOutcome() == null ? null : l.c.getOutcome().name(), l.c.getOpenedAt().toLocalDate(),
                top == null ? null : top.name(), reasons, tally.counting(),
                tally.independent(), state == null ? null : state.name(), ReportPolicy.priority(tally),
                l.tookPart(moderatorId));
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
        AuthorView author = new AuthorView(refs.contentRef(caseId, l.subjectId), Math.round(points * 10) / 10.0,
                StrikePolicy.suggest(points, top).name(), pause == null ? null : pause.getRestriction().name(),
                pause == null ? null : pause.getRestrictedUntil().toInstant(ZoneOffset.UTC));

        List<RevisionView> history = l.c.getTargetKind() == TargetKind.POST
                ? revisions.findByPostIdOrderByReplacedAtAscIdAsc(l.post.getId()).stream()
                        .map(r -> new RevisionView(r.getBody(), r.getTip(), r.getReplacedAt().toLocalDate())).toList()
                : List.of();
        List<CaseDecision> decided = actions.findByCaseRefOrderByOccurredAtAscIdAsc(l.c.getPublicId()).stream()
                .map(a -> new CaseDecision(a.getAction().name(), a.getReason().name(),
                        a.getOccurredAt().toLocalDate(), a.getActorId() == moderatorId))
                .toList();
        return new CaseDetail(summary, content(l), history, participants, author,
                possibleActions(l).stream().map(Enum::name).toList(), decided);
    }

    private ContentView content(Loaded l) {
        switch (l.c.getTargetKind()) {
            case POST -> {
                Post p = l.post;
                String placeName = null;
                if (p.getPrecision() == Precision.EXACT && p.getPlaceId() != null) {
                    placeName = places.findById(p.getPlaceId()).map(Place::getName).orElse(null);
                }
                List<MediaView> media = mediaRows.findByPostIdOrderBySortOrderAsc(p.getId()).stream()
                        .map(m -> new MediaView(m.getPublicId(), m.getWidth(), m.getHeight())).toList();
                return new ContentView(p.getType().name(), null, p.getBody(), p.getTip(), p.getCategory(),
                        p.getPrecision().name(), placeName,
                        p.getPrecision() == Precision.AREA ? p.getAreaCell() : null,
                        p.getCity() == null ? null : p.getCity().name(), p.getAudience().name(),
                        p.getExperiencedMonth() == null ? null : p.getExperiencedMonth().toString().substring(0, 7),
                        p.getDaypart() == null ? null : p.getDaypart().name(), media, null, null);
            }
            case COMMENT -> {
                String context = l.post.getBody();
                if (context.codePointCount(0, context.length()) > CONTEXT_MAX) {
                    context = context.substring(0, context.offsetByCodePoints(0, CONTEXT_MAX)) + "…";
                }
                return new ContentView(l.post.getType().name(), l.comment.getKind().name(), l.comment.getBody(), null,
                        null, null, null, null, null, l.post.getAudience().name(), null, null, List.of(), context,
                        null);
            }
            default -> {
                Profile profile = profiles.findByUserId(l.subjectId).orElse(null);
                PersonView person = profile == null ? null : new PersonView(profile.getDisplayName(),
                        profile.getHandle(), profile.getBio(), profile.getCity(),
                        ProfileLists.decode(profile.getInterests()));
                return new ContentView(null, null, null, null, null, null, null, null, null, null, null, null,
                        List.of(), null, person);
            }
        }
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
        if (l.c.getTargetKind() == TargetKind.POST) {
            l.post.moderate(state, reason, decision);
            posts.save(l.post);
        } else if (l.c.getTargetKind() == TargetKind.COMMENT) {
            l.comment.moderate(state, reason, decision);
            comments.save(l.comment);
        }
    }

    /** Closes a case: its reports are resolved; a post leaves review and its kept earlier texts are deleted (Q7). */
    private void close(Loaded l, ContentOutcome outcome, LocalDateTime now) {
        l.c.close(outcome, now);
        for (ContentReport r : l.caseReports) r.resolve();
        reports.saveAll(l.caseReports);
        if (l.c.getTargetKind() == TargetKind.POST) {
            l.post.setInReview(false);
            posts.save(l.post);
            revisions.deleteByPost(l.post.getId());
        }
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
    private void overturn(ModerationAction decision) {
        String id = decision.getPublicId();
        switch (decision.getAction()) {
            case CONTENT_COLLAPSE, CONTENT_REMOVE -> {
                ContentCase c = cases.findByPublicId(decision.getCaseRef()).orElse(null);
                if (c != null && c.getPostId() != null) {
                    posts.findById(c.getPostId()).filter(p -> id.equals(p.getModerationDecision())).ifPresent(p -> {
                        p.moderate(ModerationState.NONE, null, null);
                        posts.save(p);
                    });
                } else if (c != null && c.getCommentId() != null) {
                    comments.findById(c.getCommentId()).filter(m -> id.equals(m.getModerationDecision()))
                            .ifPresent(m -> {
                                m.moderate(ModerationState.NONE, null, null);
                                comments.save(m);
                            });
                }
            }
            default -> standings.findById(decision.getSubjectUserId())
                    .filter(s -> id.equals(s.getDecision()))
                    .ifPresent(standings::delete);
        }
        strikes.deleteByDecision(id);
    }

    /** A moderator who reported what a decision is about takes no part in its appeal either (conflict of interest). */
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
            if (c.getPostId() != null) {
                return posts.findById(c.getPostId()).map(p -> d.getPublicId().equals(p.getModerationDecision()))
                        .orElse(false);
            }
            return c.getCommentId() != null && comments.findById(c.getCommentId())
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
        if (c.getPostId() != null) return posts.findById(c.getPostId()).map(Post::getPublicId).orElse(null);
        if (c.getCommentId() != null) return comments.findById(c.getCommentId()).map(Comment::getPublicId).orElse(null);
        return null;
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
