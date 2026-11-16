package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.MomentDtos.MineResponse;
import com.safesphere.backend.social.dto.MomentDtos.MomentAuthor;
import com.safesphere.backend.social.dto.MomentDtos.MomentRequest;
import com.safesphere.backend.social.dto.MomentDtos.MomentResponse;
import com.safesphere.backend.social.dto.MomentDtos.Plan;
import com.safesphere.backend.social.dto.MomentDtos.QuestionRef;
import com.safesphere.backend.social.dto.MomentDtos.TodayResponse;
import com.safesphere.backend.social.dto.MomentDtos.When;
import com.safesphere.backend.social.entity.DailyQuestion;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.MomentMedia;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.entity.Profile;
import com.safesphere.backend.social.media.MediaStorage;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.policy.MomentTimes;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.DailyQuestionRepository;
import com.safesphere.backend.social.repository.MomentMediaRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.repository.ProfileRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Phase N4: moments and Today (docs/PHASE_N4.md). Every read goes through the one Visibility rule
 * (Relationships.momentFacts / forToday); a moment the viewer may not see is "not found", exactly like one that does
 * not exist. Others get rough times, never timestamps. Nothing is counted, ranked or recorded about who looked.
 *
 * N4-B1: text moments, Today, the life of a moment, keep, limits. Photos (B2) and the automatic text check, reports and
 * "This is about me" (B3) are not here yet: a moment is published the instant it is shared.
 */
@Service
public class MomentService {

    static final String NOT_FOUND = "Moment not found";
    static final String QUESTION_NOT_TODAY = "That question is no longer today's question";
    static final String KEEP_ONLY_LIVE = "Only a moment that is live on Today can be kept";
    static final String KEEP_FULL = "You can keep 6 moments. Un-keep one first.";
    static final int PAGE_SIZE = 20;

    /** At most this many candidate pages are read for one page of Today (a bound on work; the cursor carries on). */
    private static final int MAX_BATCHES = 5;

    /** The cursor of a first page: after every moment. */
    private static final LocalDateTime END = LocalDateTime.of(9999, 12, 31, 0, 0);

    private static final Logger log = LoggerFactory.getLogger(MomentService.class);

    public enum People { ALL, MINE }

    private final MomentRepository moments;
    private final MomentMediaRepository mediaRows;
    private final DailyQuestionRepository questions;
    private final ProfileRepository profiles;
    private final UserRepository users;
    private final Relationships relationships;
    private final SocialAccess access;
    private final MomentLimits limits;
    private final MediaStorage storage;
    private final Clock clock;

    public MomentService(MomentRepository moments, MomentMediaRepository mediaRows, DailyQuestionRepository questions,
                         ProfileRepository profiles, UserRepository users, Relationships relationships,
                         SocialAccess access, MomentLimits limits, MediaStorage storage, Clock clock) {
        this.moments = moments;
        this.mediaRows = mediaRows;
        this.questions = questions;
        this.profiles = profiles;
        this.users = users;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public MomentResponse create(long userId, MomentRequest request) {
        access.requireCanPost(userId);
        MembershipGate.Member member = access.requireMembership(userId);
        LocalDateTime now = now();

        Kind kind = parse(Kind.class, request.kind(), "Choose Say or Who's in?");
        Audience audience = parse(Audience.class, request.audience(), "Choose who can see this");
        String text = MomentPolicy.cleanText(request.text());
        LocalDateTime planAt = planAt(request.planAt());
        String planWhere = MomentPolicy.cleanLine(request.planWhere());
        MomentPolicy.check(new MomentPolicy.Draft(kind, audience, text, false, planAt, planWhere), now)
                .ifPresent(reason -> {
                    throw new InvalidRequestException(reason);
                });
        boolean newMember = MomentPolicy.isNewMember(member.verifiedAt(), now);
        if (newMember && MomentPolicy.containsLink(text, planWhere)) {
            throw new InvalidRequestException(MomentPolicy.NO_LINKS_FOR_NEW_MEMBERS);
        }
        DailyQuestion question = todaysQuestion(request.questionId(), member.communityId(), now);

        // Last, so a refused request never uses up the day's allowance.
        limits.share(userId, newMember);

        Moment moment = new Moment(userId, member.communityId(), kind, audience, text, now);
        moment.plan(planAt, planWhere);
        if (question != null) moment.answer(question.getId());
        moment.publish(now, MomentPolicy.LIFE);
        moments.saveAndFlush(moment);
        return respond(userId, List.of(moment), now).get(0);
    }

    /** One page of Today for [viewerId]: the pinned question and the live moments they may see, newest first. */
    @Transactional(readOnly = true)
    public TodayResponse today(long viewerId, String people, String cursor) {
        MembershipGate.Member member = access.requireMembership(viewerId);
        limits.readToday(viewerId);
        People whose = blank(people) ? People.ALL : parse(People.class, people, "Choose ALL or MINE");
        LocalDateTime now = now();
        long community = member.communityId();
        QuestionRef question = questions.findByCommunityIdAndQuestionDay(community, MomentTimes.indiaDay(now))
                .map(MomentService::ref).orElse(null);

        Relationships.Viewer viewer = relationships.viewer(viewerId);
        Set<Long> authors = new HashSet<>(viewer.connected());
        if (whose == People.ALL) authors.add(viewerId);
        if (authors.isEmpty()) return new TodayResponse(question, List.of(), null);

        Moment after = cursor(cursor, community);
        LocalDateTime cursorAt = after == null ? END : after.getVisibleAt();
        long cursorId = after == null ? Long.MAX_VALUE : after.getId();

        // The database leaves out what the rule would refuse anyway (people blocked either way or muted, states others
        // never see); every candidate still passes the one rule below. -1 keeps "NOT IN" valid when nobody is hidden.
        Set<Long> hidden = new HashSet<>(viewer.blockedEitherWay());
        hidden.addAll(viewer.muted());
        hidden.remove(viewerId);
        hidden.add(-1L);
        Set<ModerationState> states = Visibility.todayStates();

        // Read candidates in order until one page (and one more, to know whether another page exists) passed the rule.
        List<Moment> shown = new ArrayList<>(PAGE_SIZE + 1);
        boolean exhausted = false;
        for (int batch = 0; batch < MAX_BATCHES && shown.size() <= PAGE_SIZE && !exhausted; batch++) {
            PageRequest page = PageRequest.of(0, PAGE_SIZE * 2);
            List<Moment> candidates = whose == People.ALL
                    ? moments.todayPage(community, authors, hidden, states, now, cursorAt, cursorId, page)
                    : moments.todayPageOf(community, authors, hidden, states, now, cursorAt, cursorId, page);
            exhausted = candidates.size() < PAGE_SIZE * 2;
            if (candidates.isEmpty()) break;
            shown.addAll(relationships.forToday(viewer, community, candidates, now));
            Moment lastRead = candidates.get(candidates.size() - 1);
            cursorAt = lastRead.getVisibleAt();
            cursorId = lastRead.getId();
        }

        // The cursor is always a moment this viewer was just given, never one the rule refused. More than a page passed:
        // there is a next page. Otherwise the wall has ended, unless the work bound stopped the reading first; then
        // the next page carries on after the last moment shown.
        String next = null;
        if (shown.size() > PAGE_SIZE) {
            shown = shown.subList(0, PAGE_SIZE);
            next = shown.get(PAGE_SIZE - 1).getPublicId();
        } else if (!exhausted && !shown.isEmpty()) {
            next = shown.get(shown.size() - 1).getPublicId();
        }
        return new TodayResponse(question, respond(viewerId, shown, now), next);
    }

    @Transactional(readOnly = true)
    public MomentResponse get(long viewerId, String publicId) {
        access.requireMember(viewerId);
        LocalDateTime now = now();
        return respond(viewerId, List.of(requireVisible(viewerId, publicId, now)), now).get(0);
    }

    /** The caller's own moments: the ones that have not ended, and the ones they keep. */
    @Transactional(readOnly = true)
    public MineResponse mine(long userId) {
        access.requireMember(userId);
        LocalDateTime now = now();
        return new MineResponse(respond(userId, moments.ownCurrent(userId, now), now),
                respond(userId, moments.keptBy(userId), now), MomentPolicy.MAX_KEPT);
    }

    /**
     * N-54: a moment is never edited, only deleted. Always allowed for its author (a refusal during a review would
     * tell them that someone reported it); its photo row goes with it and the file after the commit.
     */
    @Transactional
    public void delete(long userId, String publicId) {
        access.requireMember(userId);
        Moment moment = requireOwn(userId, publicId);
        List<MomentMedia> media = mediaRows.findByMomentIdIn(List.of(moment.getId()));
        List<String> keys = media.stream().map(MomentMedia::getStorageKey).toList();
        mediaRows.deleteAll(media);
        moments.delete(moment);
        moments.flush();
        deleteFilesAfterCommit(keys);
    }

    /**
     * N-48: keep a moment on the profile. Only the author, only while it is live on Today, at most 6 at a time. The
     * account row is locked first, so two requests at once cannot both take the sixth place.
     */
    @Transactional
    public MomentResponse keep(long userId, String publicId) {
        access.requireMember(userId);
        users.findByIdForUpdate(userId);
        Moment moment = requireOwn(userId, publicId);
        LocalDateTime now = now();
        if (!moment.isKept()) {
            if (!moment.isLive(now) || !moment.getModeration().visibleToOthers()) {
                throw new ConflictException(KEEP_ONLY_LIVE);
            }
            if (moments.countKept(userId) >= MomentPolicy.MAX_KEPT) throw new ConflictException(KEEP_FULL);
            moment.keep(now);
            moments.saveAndFlush(moment);
        }
        return respond(userId, List.of(moment), now).get(0);
    }

    /** N-48: un-keep, at any time. An ended moment is then gone for everyone else and is purged in its turn. */
    @Transactional
    public void unkeep(long userId, String publicId) {
        access.requireMember(userId);
        Moment moment = requireOwn(userId, publicId);
        if (moment.isKept()) {
            moment.unkeep();
            moments.saveAndFlush(moment);
        }
    }

    /** The moment if [viewerId] may see it, else 404 (for the units that build on moments). */
    public Moment requireVisible(long viewerId, String publicId, LocalDateTime now) {
        Moment moment = blank(publicId) ? null : moments.findByPublicId(publicId).orElse(null);
        if (moment == null || !relationships.canSeeMoment(viewerId, moment, now)) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        return moment;
    }

    /**
     * Builds the answers for [list] as [viewerId] may see them (authors, profiles and questions loaded in batches). The
     * caller has already checked that the viewer may see every moment in [list] (Visibility).
     */
    public List<MomentResponse> respond(long viewerId, List<Moment> list, LocalDateTime now) {
        if (list.isEmpty()) return List.of();
        Set<Long> authorIds = list.stream().map(Moment::getAuthorId).collect(Collectors.toSet());
        Map<Long, User> people = users.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Profile> profileOf = profiles.findByUserIdIn(List.copyOf(authorIds)).stream()
                .collect(Collectors.toMap(Profile::getUserId, Function.identity()));
        Set<Long> questionIds = list.stream().map(Moment::getQuestionId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DailyQuestion> questionOf = questionIds.isEmpty() ? Map.of() : questions.findByIdIn(questionIds)
                .stream().collect(Collectors.toMap(DailyQuestion::getId, Function.identity()));

        List<MomentResponse> out = new ArrayList<>(list.size());
        for (Moment m : list) {
            boolean mine = m.getAuthorId() == viewerId;
            User person = people.get(m.getAuthorId());
            Profile profile = profileOf.get(m.getAuthorId());
            MomentAuthor author = new MomentAuthor(profile == null ? null : profile.getPublicId(),
                    profile == null ? null : profile.getHandle(), person == null ? null : person.getName(),
                    profile == null ? null : profile.getPhotoKey());
            Plan plan = m.getKind() != Kind.WHOS_IN ? null
                    : new Plan(m.getPlanAt() == null ? null : m.getPlanAt().toInstant(ZoneOffset.UTC), m.getPlanWhere());
            DailyQuestion question = m.getQuestionId() == null ? null : questionOf.get(m.getQuestionId());
            When when = null;
            if (m.getVisibleAt() != null) {
                MomentTimes.Rough rough = MomentTimes.rough(m.getVisibleAt(), now);
                when = new When(rough.day().name(), rough.part() == null ? null : rough.part().name());
            }
            String status = m.getVisibleAt() == null ? "WAITING" : m.hasEnded(now) ? "ENDED" : "LIVE";
            out.add(new MomentResponse(m.getPublicId(), m.getKind().name(), m.getAudience().name(), author, mine,
                    m.getBody(), plan, question == null ? null : ref(question), when, status, m.isKept(),
                    mine ? m.getCreatedAt().toInstant(ZoneOffset.UTC) : null,
                    mine && m.getVisibleAt() != null ? m.getVisibleAt().toInstant(ZoneOffset.UTC) : null,
                    mine && m.getExpiresAt() != null ? m.getExpiresAt().toInstant(ZoneOffset.UTC) : null));
        }
        return out;
    }

    // ---- helpers ----

    private Moment requireOwn(long userId, String publicId) {
        Moment moment = blank(publicId) ? null : moments.findByPublicId(publicId).orElse(null);
        if (moment == null || moment.getAuthorId() != userId) throw new ResourceNotFoundException(NOT_FOUND);
        return moment;
    }

    /**
     * The moment a cursor names: any moment of the viewer's community that was ever on the wall. It is only a position
     * (visible_at, id) to carry on from; nothing about it is returned, and it need not be visible any more (it may have
     * ended or been deleted while the person was reading: a deleted one makes the cursor invalid and the app reloads).
     */
    private Moment cursor(String cursor, long community) {
        if (blank(cursor)) return null;
        Moment moment = moments.findByPublicId(cursor.trim()).orElse(null);
        if (moment == null || moment.getCommunityId() != community || moment.getVisibleAt() == null) {
            throw new InvalidRequestException("Invalid cursor");
        }
        return moment;
    }

    /** The question a new moment answers: it must be the one pinned on the author's Today right now. */
    private DailyQuestion todaysQuestion(String publicId, long community, LocalDateTime now) {
        if (blank(publicId)) return null;
        DailyQuestion question = questions.findByPublicId(publicId.trim()).orElse(null);
        if (question == null || question.getCommunityId() != community
                || !question.getQuestionDay().equals(MomentTimes.indiaDay(now))) {
            throw new InvalidRequestException(QUESTION_NOT_TODAY);
        }
        return question;
    }

    /** The time of a plan, to the minute (people plan in minutes; seconds would only look exact). */
    private static LocalDateTime planAt(String raw) {
        if (blank(raw)) return null;
        try {
            return LocalDateTime.ofInstant(Instant.parse(raw.trim()), ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Choose a time within the next 24 hours");
        }
    }

    private static QuestionRef ref(DailyQuestion question) {
        return new QuestionRef(question.getPublicId(), question.getBody());
    }

    /** Photo files go only after the rows are gone for good; a file left behind is removed by the clean-up job. */
    private void deleteFilesAfterCommit(List<String> keys) {
        if (keys.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("A photo file could not be removed ({}); the clean-up job will retry",
                                e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String message) {
        if (blank(raw)) throw new InvalidRequestException(message);
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(message);
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
