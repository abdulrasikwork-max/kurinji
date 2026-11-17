package com.safesphere.backend.moderation.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.common.independence.Independence;
import com.safesphere.backend.config.ModerationProperties;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.MyReport;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ReportReceived;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ReportRequest;
import com.safesphere.backend.moderation.entity.ContentCase;
import com.safesphere.backend.moderation.entity.ContentReport;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOpenedBy;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.TargetKind;
import com.safesphere.backend.moderation.repository.ContentCaseRepository;
import com.safesphere.backend.moderation.repository.ContentReportRepository;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
import com.safesphere.backend.social.entity.ContentModeration.ModerationSource;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.entity.SocialStanding;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import com.safesphere.backend.social.repository.SocialStandingRepository;
import com.safesphere.backend.social.service.MomentService;
import com.safesphere.backend.social.service.ProfileService;
import com.safesphere.backend.social.service.SocialAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reports about moments and people (Phase 13D: EXPERIENCE_PLATFORM_DESIGN.md §8.3, §4.6, plan M3-M6; Phase N4: moments
 * instead of posts and perspectives, and "This is about me").
 *
 *   - Only what the reporter can see can be reported; anything else is "not found", like something that does not exist.
 *   - Reporting the same thing twice gives the same answer and changes nothing (no "already reported" oracle).
 *   - Nothing the reported person can observe changes on a report (M4). Their moment is collapsed only when
 *     [collapseReports] independent counting reports agree (M6, ReportPolicy), and the words they then see never
 *     mention reports. A person is never affected by reports alone.
 *   - Every report joins the open case about its target (or opens one) for a human to decide.
 *   - A new member's report reaches the moderators at once but counts toward the automatic collapse only when their
 *     7 days are over (PHASE_N4 §5).
 *   - "This is about me" (N-52) is the one report that acts at once: the moment is hidden from everyone but its author
 *     until a moderator decides. The author is told only that a moderator is looking, never that someone asked.
 */
@Service
public class ReportService {

    static final String OWN_CONTENT = "You can't report your own moments or profile";
    static final String TOO_MANY = "You have sent many reports today. Please try again tomorrow.";
    static final int MY_REPORTS = 50;

    private final ContentCaseRepository cases;
    private final ContentReportRepository reports;
    private final MomentRepository moments;
    private final SocialStandingRepository standings;
    private final MomentService momentService;
    private final ProfileService profileService;
    private final SocialAccess access;
    private final ModerationProperties properties;
    private final Clock clock;
    private final AttemptLimiter limiter;
    private final TransactionTemplate inTransaction;
    private final ProfileRepository profiles;

    public ReportService(ContentCaseRepository cases, ContentReportRepository reports, MomentRepository moments,
                         SocialStandingRepository standings, MomentService momentService,
                         ProfileService profileService, SocialAccess access,
                         ModerationProperties properties, Clock clock, PlatformTransactionManager transactions,
                         ProfileRepository profiles) {
        this.inTransaction = new TransactionTemplate(transactions);
        // Read committed: after waiting for the target's lock, the queries see what the previous report committed
        // (under MySQL's default repeatable read they would still read the snapshot from before the wait).
        this.inTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.profiles = profiles;
        this.cases = cases;
        this.reports = reports;
        this.moments = moments;
        this.standings = standings;
        this.momentService = momentService;
        this.profileService = profileService;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
        this.limiter = new AttemptLimiter(properties.reportsPerDay(), Duration.ofDays(1), 100_000, clock);
    }

    /**
     * Phase 14: one transaction per attempt. Two reports arriving together (two people opening the same case, or the
     * same person sending twice) deadlocked on MySQL's unique indexes (measured live) or broke a unique key. Each
     * attempt first locks the reported row, so reports about one target run one after the other and each sees the
     * case and reports before it. If a transaction still loses a race it is unusable, so the report is tried again in
     * a new one (at most three attempts), which answers the same 202.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportReceived report(long reporterId, ReportRequest request) {
        TargetKind kind = parse(TargetKind.class, request.targetKind(), "Choose what to report");
        if (kind != TargetKind.MOMENT && kind != TargetKind.PERSON) {
            throw new InvalidRequestException("Choose what to report");
        }
        ModerationReason reason = parse(ModerationReason.class, request.reason(), "Choose a reason");
        if (!reason.reportable()) throw new InvalidRequestException("Choose a reason");
        if (reason == ModerationReason.IMPERSONATION && kind != TargetKind.PERSON) {
            throw new InvalidRequestException("Impersonation is reported on the person's profile");
        }
        String note = note(reason, request.note());
        return retrying(() -> reportOnce(reporterId, kind, request.targetId(), reason, note));
    }

    /**
     * N-52 "This is about me": someone a moment is about asks for it to be looked at. The moment is hidden from
     * everyone but its author at once (unless a moderator has already decided about it), and a priority case is opened
     * or joined. Counted against the same daily limit as reports; the same 202 whether new or repeated.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportReceived aboutMe(long reporterId, String momentId) {
        return retrying(() -> reportOnce(reporterId, TargetKind.MOMENT, momentId, ModerationReason.ABOUT_ME, null));
    }

    private ReportReceived retrying(java.util.function.Supplier<ReportReceived> attempt) {
        for (int tries = 1; ; tries++) {
            try {
                return inTransaction.execute(s -> attempt.get());
            } catch (DataIntegrityViolationException | ConcurrencyFailureException raced) {
                if (tries == 3) throw raced;
            }
        }
    }

    private ReportReceived reportOnce(long reporterId, TargetKind kind, String targetId, ModerationReason reason,
                                      String note) {
        access.requireMember(reporterId); // a pause never stops reporting
        LocalDateTime now = now();

        // The target, as the reporter may see it (404 otherwise); never one's own.
        ContentCase open;
        if (kind == TargetKind.MOMENT) {
            // Someone who already reported or asked about this moment gets the same answer again, also when their own
            // request has hidden it from them meanwhile (no "already reported" oracle, no 404 after a 202).
            Moment known = targetId == null ? null : moments.findByPublicId(targetId).orElse(null);
            boolean aboutMe = reason == ModerationReason.ABOUT_ME;
            ContentReport earlier = known == null ? null
                    : reports.findByReporterIdAndMomentId(reporterId, known.getId()).orElse(null);
            // N4-C (N-85): an earlier OPEN report by the same person does not swallow "This is about me": it becomes
            // their about-me request below. Anything else already sent gets the same answer again.
            if (earlier != null && !(aboutMe && canBecomeAboutMe(earlier))) return received();
            Moment seen = momentService.requireVisible(reporterId, targetId, now);
            if (seen.getAuthorId() == reporterId) throw new InvalidRequestException(OWN_CONTENT);
            Moment moment = moments.lockById(seen.getId()).orElseThrow();
            ContentReport mine = reports.findByReporterIdAndMomentId(reporterId, moment.getId()).orElse(null);
            if (mine != null) {
                if (!aboutMe || !canBecomeAboutMe(mine)) return received();
                limit(reporterId);
                mine.becomeAboutMe();
                reports.saveAndFlush(mine);
                hideForAboutMe(moment);
                moment.setInReview(true);
                moments.save(moment);
                return received();
            }
            limit(reporterId);
            open = cases.findByMomentIdAndStatus(moment.getId(), CaseStatus.OPEN).orElseGet(() -> cases.saveAndFlush(
                    ContentCase.aboutMoment(moment.getId(), now)
                            .openedBy(aboutMe ? CaseOpenedBy.ABOUT_ME : CaseOpenedBy.REPORTS)));
            moment.setInReview(true); // N-47: not purged while a case needs it
            if (aboutMe) hideForAboutMe(moment);
            moments.save(moment);
        } else {
            Profile profile = profileService.requireVisible(reporterId, targetId);
            long personId = profile.getUserId();
            if (personId == reporterId) throw new InvalidRequestException(OWN_CONTENT);
            profiles.lockById(profile.getId());
            if (reports.existsByReporterIdAndPersonId(reporterId, personId)) return received();
            limit(reporterId);
            open = cases.findByPersonIdAndStatus(personId, CaseStatus.OPEN)
                    .orElseGet(() -> cases.saveAndFlush(ContentCase.aboutPerson(personId, now)));
        }
        reports.saveAndFlush(new ContentReport(open, reporterId, reason, note, now,
                access.countsFrom(reporterId, now)));
        evaluateCollapse(open, now);
        return received();
    }

    /** N-52: hidden from everyone but its author at once, unless a moderator already decided or it is held already. */
    private static void hideForAboutMe(Moment moment) {
        if (moment.getModerationSource() != ModerationSource.MODERATOR && moment.getModeration() != ModerationState.HELD) {
            moment.moderateBySystem(ModerationState.HELD, ModerationReason.ABOUT_ME, null);
        }
    }

    /** N-85: only a report still waiting for a decision can become an about-me request (a decided one stays as it is). */
    private static boolean canBecomeAboutMe(ContentReport report) {
        return !report.isResolved() && report.getReason() != ModerationReason.ABOUT_ME;
    }

    /** The caller's own reports, newest first (at most 50). */
    @Transactional(readOnly = true)
    public List<MyReport> mine(long reporterId) {
        access.requireMember(reporterId);
        LocalDateTime now = now();
        List<ContentReport> own = reports.findByReporterIdOrderByCreatedAtDescIdDesc(reporterId,
                PageRequest.of(0, MY_REPORTS));
        Map<Long, ContentCase> byId = cases.findByIdIn(own.stream().map(ContentReport::getCaseId).toList()).stream()
                .collect(Collectors.toMap(ContentCase::getId, Function.identity()));
        List<MyReport> out = new ArrayList<>();
        for (ContentReport r : own) {
            ContentCase c = byId.get(r.getCaseId());
            if (c == null) continue; // the case went with its target
            boolean reviewed = !c.isOpen();
            out.add(new MyReport(r.getPublicId(), c.getTargetKind().name(), r.getReason().name(),
                    reviewed ? "REVIEWED" : "RECEIVED", reviewed ? c.getOutcome().name() : null,
                    Periods.of(r.getCreatedAt(), now)));
        }
        return out;
    }

    /**
     * What the open reports of [c] add up to now (moderators, the queue, the collapse rule). Reporters paused in the
     * social features do not count; linked reporters count once (Independence over shared reported targets).
     */
    @Transactional(readOnly = true)
    public ReportPolicy.Tally tally(ContentCase c, LocalDateTime now) {
        return tally(reports.findByCaseIdOrderByCreatedAtAscIdAsc(c.getId()), now);
    }

    ReportPolicy.Tally tally(List<ContentReport> list, LocalDateTime now) {
        Set<Long> reporters = list.stream().map(ContentReport::getReporterId).collect(Collectors.toSet());
        Set<Long> paused = new HashSet<>();
        for (SocialStanding s : standings.findAllById(reporters)) {
            if (s.inForce(now)) paused.add(s.getUserId());
        }
        List<ReportPolicy.Vote> votes = list.stream().map(r -> new ReportPolicy.Vote(r.getReporterId(), r.getReason(),
                r.getCountsFrom(), r.isResolved(), paused.contains(r.getReporterId()))).toList();
        return ReportPolicy.tally(votes, linkage(reporters), now);
    }

    /** Independence linkage among [reporters] from the reports that exist (they fade with case retention). */
    Independence.Linkage linkage(Set<Long> reporters) {
        if (reporters.size() < 2) return Independence.Linkage.NONE;
        Map<Long, Long> totals = new HashMap<>();
        for (Object[] row : reports.totals(reporters)) totals.put((Long) row[0], (Long) row[1]);
        return Independence.fromRows(reports.sharedTargets(reporters), totals);
    }

    /**
     * M6: collapses the moment of an open case when enough independent counting reports agree. Called on each report
     * and by the hourly job (a new member's report starts counting later). Only from NONE: a moderator's or a rule's
     * state is never overridden. Returns true when it collapsed something.
     */
    @Transactional
    public boolean evaluateCollapse(ContentCase c, LocalDateTime now) {
        if (!c.isOpen() || c.getTargetKind() != TargetKind.MOMENT) return false;
        Moment moment = moments.findById(c.getMomentId()).orElse(null);
        if (moment == null || moment.getModeration() != ModerationState.NONE) return false;
        if (!ReportPolicy.collapses(tally(c, now), properties.collapseReports())) return false;
        moment.moderate(ModerationState.COLLAPSED, null, null);
        moments.save(moment);
        return true;
    }

    // ---- helpers ----

    private void limit(long reporterId) {
        if (!limiter.tryAcquire("user:" + reporterId)) throw new TooManyAttemptsException(TOO_MANY);
    }

    private static ReportReceived received() {
        return new ReportReceived("RECEIVED");
    }

    /** M3: a note of 1-200 characters exactly for OTHER (cleaned like a profile line), none otherwise. */
    private static String note(ModerationReason reason, String raw) {
        String note = MomentPolicy.cleanLine(raw);
        if (reason != ModerationReason.OTHER) {
            if (note != null) throw new InvalidRequestException("A note is only for \"Something else\"");
            return null;
        }
        if (note == null) throw new InvalidRequestException("Say in a few words what is wrong");
        if (MomentPolicy.length(note) > 200) throw new InvalidRequestException("A note has at most 200 characters");
        return note;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String raw, String message) {
        if (raw == null || raw.isBlank()) throw new InvalidRequestException(message);
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(message);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
