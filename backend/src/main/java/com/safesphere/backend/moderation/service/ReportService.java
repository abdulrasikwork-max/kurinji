package com.safesphere.backend.moderation.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.TooManyAttemptsException;
import com.safesphere.backend.community.service.Independence;
import com.safesphere.backend.config.ModerationProperties;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.MyReport;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ReportReceived;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.ReportRequest;
import com.safesphere.backend.moderation.entity.ContentCase;
import com.safesphere.backend.moderation.entity.ContentReport;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.TargetKind;
import com.safesphere.backend.moderation.repository.ContentCaseRepository;
import com.safesphere.backend.moderation.repository.ContentReportRepository;
import com.safesphere.backend.security.AttemptLimiter;
import com.safesphere.backend.social.entity.Comment;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.entity.SocialStanding;
import com.safesphere.backend.social.policy.PostPolicy;
import com.safesphere.backend.social.policy.TimeBands;
import com.safesphere.backend.social.repository.CommentRepository;
import com.safesphere.backend.social.repository.PostRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import com.safesphere.backend.social.repository.SocialStandingRepository;
import com.safesphere.backend.social.service.CommentService;
import com.safesphere.backend.social.service.PostService;
import com.safesphere.backend.social.service.ProfileService;
import com.safesphere.backend.social.service.SocialAccess;
import com.safesphere.backend.user.entity.User;
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
 * Phase 13D: reports about posts, perspectives and people (EXPERIENCE_PLATFORM_DESIGN.md §8.3, §4.6; plan M3-M6).
 *
 *   - Only what the reporter can see can be reported; anything else is "not found", like a post that does not exist.
 *   - Reporting the same thing twice gives the same answer and changes nothing (no "already reported" oracle).
 *   - Nothing the reported person can observe changes on a report (M4). Their post or perspective is collapsed only
 *     when [collapseReports] independent counting reports agree (M6, ReportPolicy), and the words they then see never
 *     mention reports. A person is never affected by reports alone.
 *   - Every report joins the open case about its target (or opens one) for a human to decide.
 */
@Service
public class ReportService {

    static final String OWN_CONTENT = "You can't report your own posts, perspectives or profile";
    static final String TOO_MANY = "You have sent many reports today. Please try again tomorrow.";
    static final int MY_REPORTS = 50;

    private final ContentCaseRepository cases;
    private final ContentReportRepository reports;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final SocialStandingRepository standings;
    private final PostService postService;
    private final CommentService commentService;
    private final ProfileService profileService;
    private final SocialAccess access;
    private final ModerationProperties properties;
    private final Clock clock;
    private final AttemptLimiter limiter;
    private final TransactionTemplate inTransaction;
    private final ProfileRepository profiles;

    public ReportService(ContentCaseRepository cases, ContentReportRepository reports, PostRepository posts,
                         CommentRepository comments, SocialStandingRepository standings, PostService postService,
                         CommentService commentService, ProfileService profileService, SocialAccess access,
                         ModerationProperties properties, Clock clock, PlatformTransactionManager transactions,
                         ProfileRepository profiles) {
        this.inTransaction = new TransactionTemplate(transactions);
        // Read committed: after waiting for the target's lock, the queries see what the previous report committed
        // (under MySQL's default repeatable read they would still read the snapshot from before the wait).
        this.inTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.profiles = profiles;
        this.cases = cases;
        this.reports = reports;
        this.posts = posts;
        this.comments = comments;
        this.standings = standings;
        this.postService = postService;
        this.commentService = commentService;
        this.profileService = profileService;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
        this.limiter = new AttemptLimiter(properties.reportsPerDay(), Duration.ofDays(1), 100_000, clock);
    }

    /**
     * Phase 14: one transaction per attempt. Two reports arriving together (two people opening the same case, or the
     * same person sending twice) deadlocked on MySQL's unique indexes (measured live) or broke a unique key. Each
     * attempt now first locks the reported row, so reports about one target run one after the other and each sees the
     * case and reports before it. If a transaction still loses a race it is unusable, so the report is tried again in
     * a new one (at most three attempts), which answers the same 202.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportReceived report(long reporterId, ReportRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return inTransaction.execute(s -> reportOnce(reporterId, request));
            } catch (DataIntegrityViolationException | ConcurrencyFailureException raced) {
                if (attempt == 3) throw raced;
            }
        }
    }

    private ReportReceived reportOnce(long reporterId, ReportRequest request) {
        User reporter = access.requireMember(reporterId); // a pause never stops reporting
        TargetKind kind = parse(TargetKind.class, request.targetKind(), "Choose what to report");
        if (kind == TargetKind.PLACE) throw new InvalidRequestException("Choose what to report");
        ModerationReason reason = parse(ModerationReason.class, request.reason(), "Choose a reason");
        if (reason == ModerationReason.NEEDS_REVIEW || reason == ModerationReason.AWAITING_CHECK) {
            throw new InvalidRequestException("Choose a reason");
        }
        if (reason == ModerationReason.IMPERSONATION && kind != TargetKind.PERSON) {
            throw new InvalidRequestException("Impersonation is reported on the person's profile");
        }
        String note = note(reason, request.note());
        LocalDateTime now = now();

        // The target, as the reporter may see it (404 otherwise); never one's own.
        ContentCase open;
        switch (kind) {
            case POST -> {
                Post post = postService.requireVisible(reporterId, request.targetId(), now);
                if (post.getAuthorId() == reporterId) throw new InvalidRequestException(OWN_CONTENT);
                posts.lockById(post.getId());
                if (reports.existsByReporterIdAndPostId(reporterId, post.getId())) return received();
                limit(reporterId);
                open = openCase(() -> cases.findByPostIdAndStatus(post.getId(), CaseStatus.OPEN),
                        () -> ContentCase.aboutPost(post.getId(), now));
                if (!post.isInReview()) {
                    post.setInReview(true); // Q7: edits from now on keep the earlier text for the moderator
                    posts.save(post);
                }
            }
            case COMMENT -> {
                Comment comment = commentService.requireVisible(reporterId, request.targetId(), now);
                if (comment.getAuthorId() == reporterId) throw new InvalidRequestException(OWN_CONTENT);
                comments.lockById(comment.getId());
                if (reports.existsByReporterIdAndCommentId(reporterId, comment.getId())) return received();
                limit(reporterId);
                open = openCase(() -> cases.findByCommentIdAndStatus(comment.getId(), CaseStatus.OPEN),
                        () -> ContentCase.aboutComment(comment.getId(), now));
            }
            default -> {
                Profile profile = profileService.requireVisible(reporterId, request.targetId());
                long personId = profile.getUserId();
                if (personId == reporterId) throw new InvalidRequestException(OWN_CONTENT);
                profiles.lockById(profile.getId());
                if (reports.existsByReporterIdAndPersonId(reporterId, personId)) return received();
                limit(reporterId);
                open = openCase(() -> cases.findByPersonIdAndStatus(personId, CaseStatus.OPEN),
                        () -> ContentCase.aboutPerson(personId, now));
            }
        }
        reports.saveAndFlush(new ContentReport(open, reporterId, reason, note, now, access.countsFrom(reporter, now)));
        evaluateCollapse(open, now);
        return received();
    }

    /** The caller's own reports, newest first (at most 50). */
    @Transactional(readOnly = true)
    public List<MyReport> mine(long reporterId) {
        access.requireMember(reporterId);
        LocalDateTime now = now();
        List<ContentReport> own = reports.findByReporterIdOrderByCreatedAtDescIdDesc(reporterId,
                PageRequest.of(0, MY_REPORTS));
        Map<Long, ContentCase> byId = cases.findByIdIn(own.stream().map(ContentReport::getCaseId).toList()).stream()
                .collect(Collectors.toMap(ContentCase::getId, c -> c));
        List<MyReport> out = new ArrayList<>();
        for (ContentReport r : own) {
            ContentCase c = byId.get(r.getCaseId());
            if (c == null) continue; // the case went with its target
            boolean reviewed = !c.isOpen();
            out.add(new MyReport(r.getPublicId(), c.getTargetKind().name(), r.getReason().name(),
                    reviewed ? "REVIEWED" : "RECEIVED", reviewed ? c.getOutcome().name() : null,
                    TimeBands.band(r.getCreatedAt(), now).name()));
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
     * M6: collapses the post or perspective of an open case when enough independent counting reports agree. Called on
     * each report and by the hourly job (a report of a new account starts counting later). Only from NONE: a moderator's
     * state is never overridden. Returns true when it collapsed something.
     */
    @Transactional
    public boolean evaluateCollapse(ContentCase c, LocalDateTime now) {
        if (!c.isOpen() || c.getTargetKind() == TargetKind.PERSON) return false;
        if (c.getTargetKind() == TargetKind.POST) {
            Post post = posts.findById(c.getPostId()).orElse(null);
            if (post == null || post.getModeration() != ModerationState.NONE) return false;
            if (!ReportPolicy.collapses(tally(c, now), properties.collapseReports())) return false;
            post.moderate(ModerationState.COLLAPSED, null, null);
            posts.save(post);
            return true;
        }
        Comment comment = comments.findById(c.getCommentId()).orElse(null);
        if (comment == null || comment.getModeration() != ModerationState.NONE) return false;
        if (!ReportPolicy.collapses(tally(c, now), properties.collapseReports())) return false;
        comment.moderate(ModerationState.COLLAPSED, null, null);
        comments.save(comment);
        return true;
    }

    // ---- helpers ----

    private interface Finder {
        Optional<ContentCase> find();
    }

    private interface Maker {
        ContentCase make();
    }

    /** The open case about a target, or a new one (a concurrent first report gets the other's case). */
    private ContentCase openCase(Finder finder, Maker maker) {
        // A simultaneous second case breaks the one-open-case key; report() then tries again (Phase 14).
        return finder.find().orElseGet(() -> cases.saveAndFlush(maker.make()));
    }

    private void limit(long reporterId) {
        if (!limiter.tryAcquire("user:" + reporterId)) throw new TooManyAttemptsException(TOO_MANY);
    }

    private static ReportReceived received() {
        return new ReportReceived("RECEIVED");
    }

    /** M3: a note of 1-200 characters exactly for OTHER (cleaned like a profile line), none otherwise. */
    private static String note(ModerationReason reason, String raw) {
        String note = PostPolicy.cleanLine(raw);
        if (reason != ModerationReason.OTHER) {
            if (note != null) throw new InvalidRequestException("A note is only for \"Something else\"");
            return null;
        }
        if (note == null) throw new InvalidRequestException("Say in a few words what is wrong");
        if (PostPolicy.length(note) > 200) throw new InvalidRequestException("A note has at most 200 characters");
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
