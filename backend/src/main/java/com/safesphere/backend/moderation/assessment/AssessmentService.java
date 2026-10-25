package com.safesphere.backend.moderation.assessment;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.ModerationAiProperties;
import com.safesphere.backend.evaluation.service.OutcomeRecorder;
import com.safesphere.backend.moderation.assessment.ContentAssessment.Status;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Action;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Context;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Decision;
import com.safesphere.backend.moderation.assessment.DecisionPolicy.Rule;
import com.safesphere.backend.moderation.assessment.Evidence.Answer;
import com.safesphere.backend.moderation.assessment.Evidence.Item;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.AssessmentView;
import com.safesphere.backend.moderation.dto.ContentModerationDtos.EvidenceView;
import com.safesphere.backend.moderation.entity.ContentCase;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOpenedBy;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.entity.ModerationEnums.TargetKind;
import com.safesphere.backend.moderation.repository.ContentCaseRepository;
import com.safesphere.backend.social.entity.Comment;
import com.safesphere.backend.social.entity.Post;
import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import com.safesphere.backend.social.entity.PostEnums.ModerationSource;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.event.ContentWritten;
import com.safesphere.backend.social.repository.CommentRepository;
import com.safesphere.backend.social.repository.PostMediaRepository;
import com.safesphere.backend.social.repository.PostRepository;
import com.safesphere.backend.social.service.SocialAccess;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 13E: the automatic check of posts and perspectives (docs/PHASE13E_PLAN.md E3-E19, E25, E27).
 *
 *   - It hears of every write someone else can see (social's ContentWritten, inside the write's transaction), asks the
 *     moderation service for evidence, lets DecisionPolicy decide, stores the assessment (no text) and applies it.
 *   - It moves content only between NONE and SYSTEM states: a collapse by reports and every moderator's state are never
 *     overridden. It never removes anything and never touches a person (no strike, no pause): only humans do that.
 *   - A HOLD opens (or joins) a content case at once, so a moderator looks soon; a COLLAPSE is reviewed when the author
 *     asks. Every human decision about it is in the audit log as in 13D.
 *   - When the service is unavailable, people are not silenced: the content is published and checked later; only a new
 *     account's content waits, at most app.moderation.ai.probation-wait-max.
 *   - Self-harm is never stored: it only sets the support flag of the author's own answer.
 *   - A failure inside the check is logged with its class only and never stops the write.
 */
@Service
public class AssessmentService {

    static final int RETRY_BATCH = 50;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    private final ModerationServiceClient client;
    private final ContentAssessmentRepository assessments;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final PostMediaRepository mediaRows;
    private final ContentCaseRepository cases;
    private final SocialAccess access;
    private final UserRepository users;
    private final ModerationAiProperties properties;
    private final OutcomeRecorder recorder;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public AssessmentService(ModerationServiceClient client, ContentAssessmentRepository assessments,
                             PostRepository posts, CommentRepository comments, PostMediaRepository mediaRows,
                             ContentCaseRepository cases, SocialAccess access, UserRepository users,
                             ModerationAiProperties properties, OutcomeRecorder recorder,
                             PlatformTransactionManager transactions, Clock clock) {
        this.client = client;
        this.assessments = assessments;
        this.posts = posts;
        this.comments = comments;
        this.mediaRows = mediaRows;
        this.cases = cases;
        this.access = access;
        this.users = users;
        this.properties = properties;
        this.recorder = recorder;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.clock = clock;
        if (!properties.active()) {
            log.warn("Automatic content check is OFF (app.moderation.ai.enabled with a MODERATION_SERVICE_KEY of 16+ "
                    + "characters turns it on); posts and perspectives are published unchecked");
        }
    }

    // ---- the write ------------------------------------------------------------------------------------------------------

    /** Inside the write's transaction (synchronous): the answer the author gets already shows the result. */
    @EventListener
    public void onContentWritten(ContentWritten event) {
        if (!properties.active()) return;
        try {
            Target target = load(event.kind() == ContentWritten.Kind.POST ? TargetKind.POST : TargetKind.COMMENT,
                    event.contentId());
            if (target == null) return;
            Optional<Answer> answer = client.assess(event.kind().name(), event.text());
            if (answer.isPresent() && answer.get().selfHarm()) event.suggestSupport();
            apply(target, answer, event.probation(), event.publicPhotos(), now(), true);
        } catch (RuntimeException e) {
            log.warn("Automatic content check failed ({}); the content is published unchecked",
                    e.getClass().getSimpleName());
        }
    }

    /** Decides, stores the assessment and applies it to the content. [fresh] = a new write (not a retry). */
    void apply(Target target, Optional<Answer> answer, boolean probation, boolean publicPhotos, LocalDateTime now,
               boolean fresh) {
        Context context = new Context(answer.isPresent(), probation, publicPhotos, properties.modelMode());
        Decision decision = answer.map(a -> DecisionPolicy.decide(a.items(), a.strongLanguage(), a.modelScores(),
                context)).orElseGet(() -> DecisionPolicy.decide(List.of(), false, Map.of(), context));

        ContentAssessment row = target.assessment().orElseGet(target::newAssessment);
        if (answer.isPresent()) {
            List<Item> items = answer.get().items();
            row.done(decision, codes(items), spans(items), answer.get().strongLanguage(), answer.get(), now);
        } else if (fresh || row.getStatus() != Status.PENDING) {
            row.pending(decision, 1, now.plus(backoff(1)), now);
        }
        assessments.save(row);

        if (answer.isPresent()) target.strongLanguage(answer.get().strongLanguage());
        if (target.source() == ModerationSource.REPORTS || target.source() == ModerationSource.MODERATOR) {
            target.save();  // a human or the reports decided: the check adds only its evidence
        } else {
            switch (decision.action()) {
                case PUBLISH -> target.system(ModerationState.NONE, null, null);
                case PUBLISH_COLLAPSED -> target.system(ModerationState.COLLAPSED, decision.reason(),
                        spans(acting(decision, answer)));
                case HOLD_FOR_REVIEW -> {
                    target.system(ModerationState.HELD, decision.reason(), spans(acting(decision, answer)));
                    if (decision.rule() != Rule.CHECK_PENDING) openCase(target, CaseOpenedBy.SYSTEM, now);
                }
            }
            target.save();
        }
        recorder.aiChecked(decision.action(), decision.rule(),
                answer.map(Answer::lexiconVersion).orElse(OutcomeRecorder.NONE));
    }

    // ---- the author ---------------------------------------------------------------------------------------------------

    /**
     * "Ask for a review" of the caller's OWN post or perspective that the automatic check collapsed or held: opens (or
     * joins) a case for a moderator. The answer is the same whether a case already existed or not (13D M4).
     */
    @Transactional
    public void requestReview(long userId, String targetKind, String targetId) {
        access.requireMember(userId);
        TargetKind kind = "POST".equals(targetKind) ? TargetKind.POST : "COMMENT".equals(targetKind)
                ? TargetKind.COMMENT : null;
        if (kind == null) throw new InvalidRequestException("Choose a post or a perspective");
        Target target = null;
        if (targetId != null) {
            target = kind == TargetKind.POST
                    ? posts.findByPublicId(targetId).map(Target::new).orElse(null)
                    : comments.findByPublicId(targetId).map(Target::new).orElse(null);
        }
        if (target == null || target.authorId() != userId || target.source() != ModerationSource.SYSTEM
                || target.state() == ModerationState.NONE) {
            throw new ResourceNotFoundException("Nothing to review");
        }
        openCase(target, CaseOpenedBy.AUTHOR_REQUEST, now());
        target.save();
    }

    // ---- moderators ---------------------------------------------------------------------------------------------------

    /** What the automatic check found about a case's post or perspective (null when it never ran or is gone). */
    @Transactional(readOnly = true)
    public AssessmentView viewFor(ContentCase c) {
        Optional<ContentAssessment> row = switch (c.getTargetKind()) {
            case POST -> assessments.findByPostId(c.getPostId());
            case COMMENT -> assessments.findByCommentId(c.getCommentId());
            default -> Optional.empty();
        };
        return row.map(AssessmentService::view).orElse(null);
    }

    /** E27: a moderator decided about content the check had collapsed or held (KEEP = the check was overruled). */
    public void reviewed(ContentCase c, ModerationState systemState, ActionType action) {
        Optional<ContentAssessment> row = c.getTargetKind() == TargetKind.POST ? assessments.findByPostId(c.getPostId())
                : assessments.findByCommentId(c.getCommentId());
        recorder.aiReviewed(systemState, action, row.map(ContentAssessment::getLexiconVersion)
                .filter(v -> v != null).orElse(OutcomeRecorder.NONE));
    }

    // ---- the job: retries, the probation wait, retention -------------------------------------------------------------

    /** Returns how many assessments changed or were deleted. Each retry runs in its own transaction. */
    public int maintain() {
        if (!properties.active()) return 0;
        LocalDateTime now = now();
        int changed = 0;
        for (Long id : ownTransaction.execute(s -> assessments.dueForRetry(Status.PENDING, now,
                PageRequest.of(0, RETRY_BATCH)))) {
            Boolean done = ownTransaction.execute(s -> retry(id, now));
            if (Boolean.TRUE.equals(done)) changed++;
        }
        Integer deleted = ownTransaction.execute(s -> assessments.deleteExpired(Status.PENDING,
                now.minus(properties.retention())));
        return changed + (deleted == null ? 0 : deleted);
    }

    private boolean retry(Long id, LocalDateTime now) {
        ContentAssessment row = assessments.findById(id).orElse(null);
        if (row == null || row.getStatus() != Status.PENDING) return false;
        Target target = load(row.getTargetKind(), row.getTargetKind() == TargetKind.POST ? row.getPostId()
                : row.getCommentId());
        if (target == null) return false;
        if (row.getCreatedAt().plus(properties.retryFor()).isBefore(now)) {
            row.giveUp();
            releaseIfWaiting(target, row);
            assessments.save(row);
            return true;
        }
        boolean probation = users.findById(target.authorId()).map(access::onProbation).orElse(false);
        Optional<Answer> answer = client.assess(target.kind().name(), target.text());
        if (answer.isPresent()) {
            apply(target, answer, probation, target.publicPhotos(mediaRows), now, false);
            return true;
        }
        row.retryLater(row.getAttempts() + 1, now.plus(backoff(row.getAttempts() + 1)));
        if (row.getCreatedAt().plus(properties.probationWaitMax()).isBefore(now)) releaseIfWaiting(target, row);
        assessments.save(row);
        return false;
    }

    /** A new account's content that waited long enough for an unavailable check is published (E7). */
    private void releaseIfWaiting(Target target, ContentAssessment row) {
        if (target.source() == ModerationSource.SYSTEM && target.state() == ModerationState.HELD
                && target.reason() == ModerationReason.AWAITING_CHECK) {
            target.system(ModerationState.NONE, null, null);
            target.save();
        }
        if (row.getAction() == Action.HOLD_FOR_REVIEW && row.getRule() == Rule.CHECK_PENDING) {
            row.released();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private void openCase(Target target, CaseOpenedBy origin, LocalDateTime now) {
        Optional<ContentCase> open = target.kind() == TargetKind.POST
                ? cases.findByPostIdAndStatus(target.id(), CaseStatus.OPEN)
                : cases.findByCommentIdAndStatus(target.id(), CaseStatus.OPEN);
        if (open.isEmpty()) {
            cases.save((target.kind() == TargetKind.POST ? ContentCase.aboutPost(target.id(), now)
                    : ContentCase.aboutComment(target.id(), now)).openedBy(origin));
        }
        if (target.post != null) target.post.setInReview(true); // Q7: edits from now on keep the earlier text
    }

    /** The findings that made the decision act (the phrases the author is shown); none for a publish. */
    static List<Item> acting(Decision decision, Optional<Answer> answer) {
        if (answer.isEmpty()) return List.of();
        List<Item> live = answer.get().items().stream().filter(e -> !e.quoted()).toList();
        return switch (decision.rule()) {
            case SEVERE, SPAM -> live.stream().filter(DecisionPolicy::holds).toList();
            case TARGETED -> live.stream().filter(DecisionPolicy::collapses).toList();
            default -> List.of();
        };
    }

    static String codes(List<Item> items) {
        return items.isEmpty() ? null : items.stream().map(Item::code).collect(Collectors.joining(";"));
    }

    static String spans(List<Item> items) {
        return items.isEmpty() ? null : items.stream().map(e -> e.start() + "-" + e.end())
                .collect(Collectors.joining(","));
    }

    static Duration backoff(int attempts) {
        long minutes = 1L << Math.min(Math.max(attempts - 1, 0), 10);
        Duration wait = Duration.ofMinutes(minutes);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    static AssessmentView view(ContentAssessment row) {
        List<EvidenceView> evidence = new ArrayList<>();
        if (row.getEvidence() != null) {
            String[] codes = row.getEvidence().split(";");
            String[] spans = row.getSpans() == null ? new String[0] : row.getSpans().split(",");
            for (int i = 0; i < codes.length; i++) {
                String[] parts = codes[i].split(":");
                int start = -1;
                int end = -1;
                if (i < spans.length) {
                    String[] ends = spans[i].split("-");
                    start = Integer.parseInt(ends[0]);
                    end = Integer.parseInt(ends[1]);
                }
                evidence.add(new EvidenceView(parts[0], Integer.parseInt(parts[1]), "T".equals(parts[2]),
                        "Q".equals(parts[3]), start, end));
            }
        }
        return new AssessmentView(row.getStatus().name(), row.getAction().name(), row.getRule().name(),
                row.getReason() == null ? null : row.getReason().name(), evidence, row.isStrongLanguage(),
                row.getNormaliserVersion(), row.getLexiconVersion(), row.getModelVersion(), row.getPolicyVersion());
    }

    private Target load(TargetKind kind, Long id) {
        if (id == null) return null;
        return kind == TargetKind.POST ? posts.findById(id).map(Target::new).orElse(null)
                : comments.findById(id).map(Target::new).orElse(null);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** A post or a perspective, seen the same way. */
    final class Target {
        final Post post;
        final Comment comment;

        Target(Post post) {
            this.post = post;
            this.comment = null;
        }

        Target(Comment comment) {
            this.post = null;
            this.comment = comment;
        }

        TargetKind kind() {
            return post != null ? TargetKind.POST : TargetKind.COMMENT;
        }

        long id() {
            return post != null ? post.getId() : comment.getId();
        }

        long authorId() {
            return post != null ? post.getAuthorId() : comment.getAuthorId();
        }

        String text() {
            return post != null ? ContentWritten.postText(post.getBody(), post.getTip()) : comment.getBody();
        }

        ModerationSource source() {
            return post != null ? post.getModerationSource() : comment.getModerationSource();
        }

        ModerationState state() {
            return post != null ? post.getModeration() : comment.getModeration();
        }

        ModerationReason reason() {
            return post != null ? post.getModerationReason() : comment.getModerationReason();
        }

        boolean publicPhotos(PostMediaRepository media) {
            return post != null && post.getAudience() == Audience.EVERYONE
                    && !media.findByPostIdOrderBySortOrderAsc(post.getId()).isEmpty();
        }

        void system(ModerationState state, ModerationReason reason, String spans) {
            if (post != null) post.moderateBySystem(state, reason, spans);
            else comment.moderateBySystem(state, reason, spans);
        }

        void strongLanguage(boolean value) {
            if (post != null) post.setStrongLanguage(value);
            else comment.setStrongLanguage(value);
        }

        void save() {
            if (post != null) posts.save(post);
            else comments.save(comment);
        }

        Optional<ContentAssessment> assessment() {
            return post != null ? assessments.findByPostId(post.getId()) : assessments.findByCommentId(comment.getId());
        }

        ContentAssessment newAssessment() {
            return post != null ? ContentAssessment.ofPost(post.getId()) : ContentAssessment.ofComment(comment.getId());
        }

        @Override
        public String toString() {
            return kind().name().toLowerCase(Locale.ROOT) + " " + id();
        }
    }
}
