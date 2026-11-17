package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.MomentDtos.MomentResponse;
import com.safesphere.backend.social.dto.MomentDtos.People;
import com.safesphere.backend.social.dto.MomentDtos.Person;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.MomentSignal;
import com.safesphere.backend.social.entity.PlanPerson;
import com.safesphere.backend.social.entity.ResponseEnums.PlanState;
import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
import com.safesphere.backend.social.event.MomentMoreAdded;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.policy.ResponsePolicy;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.repository.MomentSignalRepository;
import com.safesphere.backend.social.repository.PlanPersonRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase N5 (docs/PHASE_N5.md): Same, Say more and its one follow-up, I'm in and the author's answers, Call it off.
 * Every response needs the one rule (Visibility.canRespond) and a member who may respond (no responses pause, N-95). A
 * moment the viewer may not see is "not found", exactly as in N4. Nothing here is counted for anyone but internally.
 */
@Service
public class ResponseService {

    private final MomentRepository moments;
    private final MomentSignalRepository signals;
    private final PlanPersonRepository planPeople;
    private final MomentService momentService;
    private final Relationships relationships;
    private final SocialAccess access;
    private final ResponseLimits limits;
    private final PeopleLookup people;
    private final PlanEvents planEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate inTransaction;

    public ResponseService(MomentRepository moments, MomentSignalRepository signals, PlanPersonRepository planPeople,
                           MomentService momentService, Relationships relationships, SocialAccess access,
                           ResponseLimits limits, PeopleLookup people, PlanEvents planEvents,
                           ApplicationEventPublisher events, Clock clock, PlatformTransactionManager transactions) {
        this.moments = moments;
        this.signals = signals;
        this.planPeople = planPeople;
        this.momentService = momentService;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.people = people;
        this.planEvents = planEvents;
        this.events = events;
        this.clock = clock;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    // ---- Same and Say more (N-88, N-89) ------------------------------------------------------------------------------

    /**
     * Turns a signal on or off. Not in one transaction: the row is written in its own, so two taps arriving together
     * end in one row, never in a 500 (the Phase 14 rule).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MomentResponse signal(long userId, String momentId, SignalKind kind, boolean on) {
        access.requireCanRespond(userId);
        LocalDateTime now = now();
        Moment moment = requireRespondable(userId, momentId, now);
        if (!ResponsePolicy.fits(kind, moment.getKind())) throw new InvalidRequestException(ResponsePolicy.NOT_ON_THIS_KIND);
        if (kind == SignalKind.SAY_MORE && !on && moment.getMoreText() != null) {
            // The author already answered: an ask cannot be taken back any more (it was answered).
            return answer(userId, moment);
        }
        var existing = signals.findByMomentIdAndUserIdAndKind(moment.getId(), userId, kind);
        if (on && existing.isEmpty()) {
            limits.signal(userId);
            try {
                signals.saveAndFlush(new MomentSignal(moment.getId(), userId, kind, now));
            } catch (DataIntegrityViolationException e) {
                // The same tap arrived twice at once: the row exists, which is what was asked.
            }
        } else if (!on && existing.isPresent()) {
            limits.signal(userId);
            signals.delete(existing.get());
        }
        return answer(userId, moment);
    }

    /**
     * N-89: the author's ONE follow-up to "Say more": a Say of theirs that is on Today, after at least one person asked,
     * once. The text check may change the moment's state (MomentMoreAdded): the follow-up is part of its words.
     */
    @Transactional
    public MomentResponse more(long userId, String momentId, String rawText) {
        access.requireCanRespond(userId);
        MembershipGate.Member member = access.requireMembership(userId);
        LocalDateTime now = now();
        Moment moment = lockOwn(userId, momentId);
        if (moment.getKind() != Kind.SAY) throw new InvalidRequestException(ResponsePolicy.NOT_ON_THIS_KIND);
        if (!moment.isLive(now) || !moment.getModeration().visibleToOthers()) {
            throw new ConflictException(ResponsePolicy.NOT_ON_TODAY);
        }
        if (moment.getMoreText() != null) throw new ConflictException(ResponsePolicy.MORE_ONCE);
        if (!signals.existsByMomentIdAndKind(moment.getId(), SignalKind.SAY_MORE)) {
            throw new ConflictException(ResponsePolicy.MORE_NOT_ASKED);
        }
        String text = MomentPolicy.cleanText(rawText);
        int length = MomentPolicy.length(text);
        if (length == 0) throw new InvalidRequestException("Write what you want to add");
        if (length > ResponsePolicy.TEXT_MAX) throw new InvalidRequestException("A follow-up has at most 500 characters");
        boolean newMember = MomentPolicy.isNewMember(member.verifiedAt(), now);
        if (newMember && MomentPolicy.containsLink(text)) {
            throw new InvalidRequestException(MomentPolicy.NO_LINKS_FOR_NEW_MEMBERS);
        }
        moment.addMore(text, now);
        moments.saveAndFlush(moment);
        MomentMoreAdded added = new MomentMoreAdded(moment.getId(), userId, newMember, text);
        events.publishEvent(added);
        moments.saveAndFlush(moment);
        MomentResponse response = answer(userId, moment);
        return added.supportSuggested() ? response.withSupport() : response;
    }

    // ---- I'm in (N-90) ------------------------------------------------------------------------------------------------

    /** Asks to join a Who's in? that takes people now. Asking again is the same answer; a removed person cannot ask. */
    @Transactional
    public MomentResponse ask(long userId, String momentId) {
        access.requireCanRespond(userId);
        MembershipGate.Member member = access.requireMembership(userId);
        LocalDateTime now = now();
        Moment seen = requireRespondable(userId, momentId, now);
        if (seen.getKind() != Kind.WHOS_IN) throw new InvalidRequestException(ResponsePolicy.NOT_ON_THIS_KIND);
        Moment moment = moments.lockById(seen.getId()).orElseThrow();
        var existing = planPeople.findByMomentIdAndUserId(moment.getId(), userId);
        if (existing.isPresent()) {
            if (existing.get().getState() == PlanState.REMOVED) throw new ConflictException(ResponsePolicy.CANNOT_ASK_AGAIN);
            return answer(userId, moment); // asked or in already: the same answer
        }
        if (!ResponsePolicy.asksOpen(true, moment.isCalledOff(), moment.getPlanAt(), now)) {
            throw new ConflictException(ResponsePolicy.ASKS_CLOSED);
        }
        limits.ask(userId, MomentPolicy.isNewMember(member.verifiedAt(), now));
        planPeople.saveAndFlush(new PlanPerson(moment.getId(), userId, now));
        return answer(userId, moment);
    }

    /**
     * The person takes their ask back, or leaves a plan they are in ("… can't make it"). Always allowed while they have a
     * row, also after the moment left Today or while it is held: nobody is ever trapped in a plan. A removed person has
     * nothing to withdraw.
     */
    @Transactional
    public void withdraw(long userId, String momentId) {
        access.requireMember(userId);
        Moment moment = momentId == null ? null : moments.findByPublicId(momentId.trim()).orElse(null);
        PlanPerson row = moment == null ? null : planPeople.findByMomentIdAndUserId(moment.getId(), userId).orElse(null);
        if (row == null || row.getState() == PlanState.REMOVED) throw new ResourceNotFoundException(MomentService.NOT_FOUND);
        boolean wasIn = row.getState() == PlanState.IN;
        planPeople.delete(row);
        planPeople.flush();
        if (wasIn) planEvents.left(moment, userId, now());
    }

    /** The author adds someone who asked (only while the plan takes people). */
    @Transactional
    public People add(long authorId, String momentId, String askId) {
        access.requireMember(authorId);
        LocalDateTime now = now();
        Moment moment = lockOwnPlan(authorId, momentId);
        if (!ResponsePolicy.asksOpen(moment.isLive(now) && moment.getModeration().visibleToOthers(),
                moment.isCalledOff(), moment.getPlanAt(), now)) {
            throw new ConflictException(ResponsePolicy.ASKS_CLOSED);
        }
        PlanPerson row = requireAsk(moment, askId);
        if (row.getState() == PlanState.ASKED) {
            if (!relationships.canSeeMoment(row.getUserId(), moment, now)) {
                throw new ResourceNotFoundException("That ask is no longer here");
            }
            row.add(now);
            planPeople.saveAndFlush(row);
            planEvents.added(moment, row.getUserId(), now);
        }
        return peopleOf(authorId, moment, now);
    }

    /** The author's silent "Not this time" (or its undo): only their own list changes (N-90). */
    @Transactional
    public People setAside(long authorId, String momentId, String askId, boolean aside) {
        access.requireMember(authorId);
        LocalDateTime now = now();
        Moment moment = lockOwnPlan(authorId, momentId);
        PlanPerson row = requireAsk(moment, askId);
        if (row.getState() == PlanState.ASKED) {
            row.setAside(aside);
            planPeople.saveAndFlush(row);
        }
        return peopleOf(authorId, moment, now);
    }

    /** The author removes someone who is in: they leave the plan and cannot ask again; nobody else is told why. */
    @Transactional
    public People remove(long authorId, String momentId, String askId) {
        access.requireMember(authorId);
        LocalDateTime now = now();
        Moment moment = lockOwnPlan(authorId, momentId);
        PlanPerson row = requireAsk(moment, askId);
        if (row.getState() == PlanState.IN) {
            row.remove(now);
            planPeople.saveAndFlush(row);
            planEvents.removed(moment, row.getUserId(), now);
        }
        return peopleOf(authorId, moment, now);
    }

    /** N-90: the author calls the plan off: asks close, the plan closes, everyone who can see the moment sees it. */
    @Transactional
    public MomentResponse callOff(long authorId, String momentId) {
        access.requireMember(authorId);
        LocalDateTime now = now();
        Moment moment = lockOwnPlan(authorId, momentId);
        if (!moment.isCalledOff()) {
            if (moment.getVisibleAt() == null) throw new ConflictException("Others cannot see this plan yet");
            moment.callOff(now);
            moments.saveAndFlush(moment);
            planEvents.calledOff(moment, now);
        }
        return answer(authorId, moment);
    }

    /**
     * The people of a moment: the author gets every list (who feels the same, who wants more, who asks, who they set
     * aside, who is in); someone in the plan gets who is in; anyone else: not found. Only people the viewer may see.
     */
    @Transactional(readOnly = true)
    public People people(long viewerId, String momentId) {
        access.requireMember(viewerId);
        LocalDateTime now = now();
        Moment moment = momentId == null ? null : moments.findByPublicId(momentId.trim()).orElse(null);
        if (moment == null) throw new ResourceNotFoundException(MomentService.NOT_FOUND);
        if (moment.getAuthorId() == viewerId) return peopleOf(viewerId, moment, now);
        boolean in = planPeople.existsByMomentIdAndUserIdAndState(moment.getId(), viewerId, PlanState.IN);
        if (!in || !Visibility.canSeeSpace(relationships.momentFacts(viewerId, moment, now), true)) {
            throw new ResourceNotFoundException(MomentService.NOT_FOUND);
        }
        Relationships.Viewer viewer = people.viewer(viewerId);
        List<Long> inIds = inOrder(planPeople.findByMomentIdAndStateOrderByAnsweredAtAscIdAsc(moment.getId(), PlanState.IN));
        List<Long> withAuthor = new java.util.ArrayList<>();
        withAuthor.add(moment.getAuthorId());
        withAuthor.addAll(inIds);
        return new People(List.of(), List.of(), List.of(), List.of(),
                people.people(viewer, people.visible(viewer, withAuthor), Map.of()));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private People peopleOf(long authorId, Moment moment, LocalDateTime now) {
        Relationships.Viewer viewer = people.viewer(authorId);
        List<Person> same = List.of();
        List<Person> sayMore = List.of();
        if (moment.getKind() == Kind.SAY) {
            same = people.people(viewer, people.visible(viewer, signalUsers(moment, SignalKind.SAME)), Map.of());
            sayMore = people.people(viewer, people.visible(viewer, signalUsers(moment, SignalKind.SAY_MORE)), Map.of());
        }
        List<PlanPerson> rows = moment.getKind() == Kind.WHOS_IN
                ? planPeople.findByMomentIdOrderByAskedAtAscIdAsc(moment.getId()) : List.of();
        Map<Long, String> refs = new HashMap<>();
        rows.forEach(r -> refs.put(r.getUserId(), r.getPublicId()));
        List<Person> asking = list(viewer, rows, r -> r.getState() == PlanState.ASKED && !r.isSetAside(), refs);
        List<Person> aside = list(viewer, rows, r -> r.getState() == PlanState.ASKED && r.isSetAside(), refs);
        List<Long> in = inOrder(rows.stream().filter(r -> r.getState() == PlanState.IN).toList());
        return new People(same, sayMore, asking, aside, people.people(viewer, people.visible(viewer, in), refs));
    }

    private List<Person> list(Relationships.Viewer viewer, List<PlanPerson> rows, Predicate<PlanPerson> which,
                              Map<Long, String> refs) {
        List<Long> ids = rows.stream().filter(which).map(PlanPerson::getUserId).toList();
        return people.people(viewer, people.visible(viewer, ids), refs);
    }

    private List<Long> signalUsers(Moment moment, SignalKind kind) {
        return signals.findByMomentIdAndKindOrderByCreatedAtDescIdDesc(moment.getId(), kind).stream()
                .map(MomentSignal::getUserId).toList();
    }

    private static List<Long> inOrder(List<PlanPerson> rows) {
        return rows.stream().sorted(Comparator.comparing(PlanPerson::getAnsweredAt)).map(PlanPerson::getUserId).toList();
    }

    /** A moment the caller may respond to now (Visibility.canRespond); else 404 or 409 with our words. */
    private Moment requireRespondable(long userId, String momentId, LocalDateTime now) {
        Moment moment = momentService.requireVisible(userId, momentId == null ? null : momentId.trim(), now);
        if (moment.getAuthorId() == userId) throw new InvalidRequestException(ResponsePolicy.OWN_MOMENT);
        if (!Visibility.canRespond(relationships.momentFacts(userId, moment, now))) {
            throw new ConflictException(ResponsePolicy.NOT_ON_TODAY);
        }
        return moment;
    }

    private Moment lockOwn(long userId, String momentId) {
        Moment moment = momentId == null ? null : moments.findByPublicId(momentId.trim()).orElse(null);
        if (moment == null || moment.getAuthorId() != userId) throw new ResourceNotFoundException(MomentService.NOT_FOUND);
        return moments.lockById(moment.getId()).orElseThrow();
    }

    private Moment lockOwnPlan(long userId, String momentId) {
        Moment moment = lockOwn(userId, momentId);
        if (moment.getKind() != Kind.WHOS_IN) throw new InvalidRequestException(ResponsePolicy.NOT_ON_THIS_KIND);
        return moment;
    }

    private PlanPerson requireAsk(Moment moment, String askId) {
        return (askId == null ? java.util.Optional.<PlanPerson>empty()
                : planPeople.findByPublicIdAndMomentId(askId.trim(), moment.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("That ask is no longer here"));
    }

    private MomentResponse answer(long viewerId, Moment moment) {
        LocalDateTime now = now();
        return momentService.respond(viewerId, List.of(moments.findById(moment.getId()).orElse(moment)), now).get(0);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
