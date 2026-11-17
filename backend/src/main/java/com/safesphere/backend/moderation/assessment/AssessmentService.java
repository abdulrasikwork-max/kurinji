package com.safesphere.backend.moderation.assessment;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.ModerationAiProperties;
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
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseOpenedBy;
import com.safesphere.backend.moderation.entity.ModerationEnums.CaseStatus;
import com.safesphere.backend.moderation.repository.ContentCaseRepository;
import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
import com.safesphere.backend.social.entity.ContentModeration.ModerationSource;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.event.MomentShared;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.service.SocialAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * The automatic check of what people write (Phase 13E, docs/PHASE13E_PLAN.md E3-E19, E25, E27; since Phase N4 of
 * moments: their text and their "where"). Rules only (N-55): no learned model acts.
 *
 *   - It hears of every moment that is shared (social's MomentShared, inside the write's transaction, BEFORE the moment
 *     is published), asks the moderation service for evidence, lets DecisionPolicy decide, stores the assessment (no
 *     text) and applies it.
 *   - It moves content only between NONE and SYSTEM states: a collapse by reports and every moderator's state are never
 *     overridden. It never removes anything and never touches a person (no strike, no pause): only humans do that.
 *   - A HOLD opens (or joins) a content case at once, so a moderator looks soon; a COLLAPSE is reviewed when the author
 *     asks. Every human decision about it is in the audit log as in 13D.
 *   - When the service is unavailable, people are not silenced: the moment is published and checked later; only a new
 *     member's words wait, at most app.moderation.ai.probation-wait-max (2 hours), then publish.
 *   - A new member's photo for the whole community is held by social itself (N-57) and "This is about me" by a
 *     person's request (N-52). This class opens the moderator's case for a held photo and NEVER changes either state:
 *     not when the check passes, not when it gives up, not when the service is down. Only a person releases them.
 *   - Self-harm is never stored: it only sets the support flag of the author's own answer.
 *   - A failure inside the check is logged with its class only and never stops the write.
 */
@Service
public class AssessmentService {

    static final int RETRY_BATCH = 50;
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    /** The "kind" sent to the text service. It knows POST and COMMENT; a moment is checked with the rules of a post. */
    static final String KIND = "POST";

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);

    private final ModerationServiceClient client;
    private final ContentAssessmentRepository assessments;
    private final MomentRepository moments;
    private final ContentCaseRepository cases;
    private final SocialAccess access;
    private final ModerationAiProperties properties;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    public AssessmentService(ModerationServiceClient client, ContentAssessmentRepository assessments,
                             MomentRepository moments, ContentCaseRepository cases, SocialAccess access,
                             ModerationAiProperties properties,
                             PlatformTransactionManager transactions, Clock clock) {
        this.client = client;
        this.assessments = assessments;
        this.moments = moments;
        this.cases = cases;
        this.access = access;
        this.properties = properties;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.clock = clock;
        if (!properties.active()) {
            log.warn("Automatic content check is OFF (app.moderation.ai.enabled with a MODERATION_SERVICE_KEY of 16+ "
                    + "characters turns it on); the words of moments are published unchecked");
        }
    }

    // ---- the write ------------------------------------------------------------------------------------------------------

    /** Inside the write's transaction (synchronous): the answer the author gets already shows the result. */
    @EventListener
    public void onMomentShared(MomentShared event) {
        try {
            Target target = load(event.momentId());
            if (target == null) return;
            LocalDateTime now = now();
            // N-57: the case for a held photo is opened whether or not the text check is running.
            if (event.photoHeld()) {
                openCase(target, CaseOpenedBy.SYSTEM, now);
                target.save();
            }
            if (!properties.active() || event.text() == null) return;
            Optional<Answer> answer = client.assess(KIND, event.text());
            if (answer.isPresent() && answer.get().selfHarm()) event.suggestSupport();
            apply(target, answer, event.newMember(), now, true);
        } catch (RuntimeException e) {
            log.warn("Automatic content check failed ({}); the content is published unchecked",
                    e.getClass().getSimpleName());
        }
    }

    /** Decides, stores the assessment and applies it to the moment. [fresh] = a new write (not a retry). */
    void apply(Target target, Optional<Answer> answer, boolean newMember, LocalDateTime now, boolean fresh) {
        // "Public photos" is always false: the photo hold is social's own rule (N-57), not this policy's.
        Context context = new Context(answer.isPresent(), newMember, false, properties.modelMode());
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
        if (target.source() == ModerationSource.REPORTS || target.source() == ModerationSource.MODERATOR
                || target.heldForAPerson()) {
            // A human or the reports decided, or the moment waits for a person (N-57, N-52): the check adds only its
            // evidence and never changes the state.
            target.save();
        } else {
            switch (decision.action()) {
                case PUBLISH -> target.release(now, fresh);
                case PUBLISH_COLLAPSED -> {
                    target.system(ModerationState.COLLAPSED, decision.reason(), spans(acting(decision, answer)));
                    if (!fresh) target.publish(now); // a retry found something mild: shown, behind a tap
                }
                case HOLD_FOR_REVIEW -> {
                    target.system(ModerationState.HELD, decision.reason(), spans(acting(decision, answer)));
                    if (decision.rule() != Rule.CHECK_PENDING) openCase(target, CaseOpenedBy.SYSTEM, now);
                }
            }
            target.save();
        }
    }

    // ---- the author ---------------------------------------------------------------------------------------------------

    /**
     * "Ask for a review" of the caller's OWN moment that a rule collapsed or held: opens (or joins) a case for a
     * moderator. The answer is the same whether a case already existed or not (13D M4).
     */
    @Transactional
    public void requestReview(long userId, String targetKind, String targetId) {
        access.requireAccount(userId); // Phase N3: the author's own content
        if (!"MOMENT".equals(targetKind)) throw new InvalidRequestException("Choose a moment");
        Target target = targetId == null ? null : moments.findByPublicId(targetId).map(Target::new).orElse(null);
        if (target == null || target.authorId() != userId || target.source() != ModerationSource.SYSTEM
                || target.state() == ModerationState.NONE) {
            throw new ResourceNotFoundException("Nothing to review");
        }
        openCase(target, CaseOpenedBy.AUTHOR_REQUEST, now());
        target.save();
    }

    // ---- moderators ---------------------------------------------------------------------------------------------------

    /** What the automatic check found about a case's moment (null when it never ran or is gone). */
    @Transactional(readOnly = true)
    public AssessmentView viewFor(ContentCase c) {
        if (c.getMomentId() == null) return null;
        return assessments.findByMomentId(c.getMomentId()).map(AssessmentService::view).orElse(null);
    }

    // ---- the job: retries, the new-member wait, retention -------------------------------------------------------------

    /** Returns how many assessments changed or were deleted. Each retry runs in its own transaction. */
    public int maintain() {
        Integer orphans = ownTransaction.execute(s -> assessments.deleteOrphans());
        int changed = orphans == null ? 0 : orphans;
        if (!properties.active()) return changed;
        LocalDateTime now = now();
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
        Target target = load(row.getMomentId());
        if (target == null) return false;
        if (row.getCreatedAt().plus(properties.retryFor()).isBefore(now)) {
            row.giveUp();
            releaseIfWaiting(target, row, now);
            assessments.save(row);
            return true;
        }
        boolean newMember = access.isNewMember(target.authorId(), now);
        Optional<Answer> answer = client.assess(KIND, target.text());
        if (answer.isPresent()) {
            apply(target, answer, newMember, now, false);
            return true;
        }
        row.retryLater(row.getAttempts() + 1, now.plus(backoff(row.getAttempts() + 1)));
        if (row.getCreatedAt().plus(properties.probationWaitMax()).isBefore(now)) releaseIfWaiting(target, row, now);
        assessments.save(row);
        return false;
    }

    /**
     * A new member's words that waited long enough for an unavailable check are published (E7); their 24 hours start
     * now. Only AWAITING_CHECK is ever released here: never a photo hold, never "This is about me".
     */
    private void releaseIfWaiting(Target target, ContentAssessment row, LocalDateTime now) {
        if (target.source() == ModerationSource.SYSTEM && target.state() == ModerationState.HELD
                && target.reason() == ModerationReason.AWAITING_CHECK) {
            target.release(now, false);
            target.save();
        }
        if (row.getAction() == Action.HOLD_FOR_REVIEW && row.getRule() == Rule.CHECK_PENDING) {
            row.released();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private void openCase(Target target, CaseOpenedBy origin, LocalDateTime now) {
        if (!cases.existsByMomentIdAndStatus(target.id(), CaseStatus.OPEN)) {
            cases.save(ContentCase.aboutMoment(target.id(), now).openedBy(origin));
        }
        target.moment.setInReview(true); // N-47: not purged while a case needs it
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

    private Target load(Long id) {
        return id == null ? null : moments.findById(id).map(Target::new).orElse(null);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** A moment, as the check sees it. */
    final class Target {
        final Moment moment;

        Target(Moment moment) {
            this.moment = moment;
        }

        long id() {
            return moment.getId();
        }

        long authorId() {
            return moment.getAuthorId();
        }

        String text() {
            return MomentShared.words(moment.getBody(), moment.getPlanWhere());
        }

        ModerationSource source() {
            return moment.getModerationSource();
        }

        ModerationState state() {
            return moment.getModeration();
        }

        ModerationReason reason() {
            return moment.getModerationReason();
        }

        /** Held until a PERSON decides (N-57, N-52): no automatic rule may change it. */
        boolean heldForAPerson() {
            return state() == ModerationState.HELD && (reason() == ModerationReason.NEW_MEMBER_PHOTO
                    || reason() == ModerationReason.ABOUT_ME);
        }

        void system(ModerationState state, ModerationReason reason, String spans) {
            moment.moderateBySystem(state, reason, spans);
        }

        /**
         * Nothing found: no state. On a fresh write social publishes the moment itself right after the listener; on a
         * later run (a retry, the end of the 2-hour wait) its 24 hours start here if it was never visible.
         */
        void release(LocalDateTime now, boolean fresh) {
            moment.moderateBySystem(ModerationState.NONE, null, null);
            if (!fresh) publish(now);
        }

        void publish(LocalDateTime now) {
            moment.publish(now, MomentPolicy.LIFE);
        }

        void strongLanguage(boolean value) {
            moment.setStrongLanguage(value);
        }

        void save() {
            moments.save(moment);
        }

        Optional<ContentAssessment> assessment() {
            return assessments.findByMomentId(moment.getId());
        }

        ContentAssessment newAssessment() {
            return ContentAssessment.ofMoment(moment.getId());
        }

        @Override
        public String toString() {
            return "moment " + id();
        }
    }
}
