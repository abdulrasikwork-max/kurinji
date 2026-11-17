package com.safesphere.backend.social.service;

import com.safesphere.backend.social.dto.MomentDtos.ForAuthor;
import com.safesphere.backend.social.dto.MomentDtos.More;
import com.safesphere.backend.social.dto.MomentDtos.PlanView;
import com.safesphere.backend.social.dto.MomentDtos.Responses;
import com.safesphere.backend.social.dto.MomentDtos.SameView;
import com.safesphere.backend.social.dto.MomentDtos.SayMoreView;
import com.safesphere.backend.social.dto.MomentDtos.When;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.MomentSignal;
import com.safesphere.backend.social.entity.PlanPerson;
import com.safesphere.backend.social.entity.ResponseEnums.PlanState;
import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
import com.safesphere.backend.social.policy.MomentTimes;
import com.safesphere.backend.social.policy.ResponsePolicy;
import com.safesphere.backend.social.repository.MomentSignalRepository;
import com.safesphere.backend.social.repository.PlanPersonRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Phase N5: the [Responses] part of moment answers, for a page of moments at once (a few queries for the whole page).
 * The caller has already checked that the viewer may see every moment. Others never get a number (N-88): the Same band
 * is a word chosen from a count that stays here; the author gets names, and only names the author may see.
 */
@Component
public class ResponseViews {

    /** How many names the author's lines carry; the rest are behind a tap (GET /api/moments/{id}/people). */
    static final int NAMES_IN_A_LINE = 3;

    private final MomentSignalRepository signals;
    private final PlanPersonRepository planPeople;
    private final PeopleLookup people;
    private final PlanSpaces spaces;

    public ResponseViews(MomentSignalRepository signals, PlanPersonRepository planPeople, PeopleLookup people,
                         PlanSpaces spaces) {
        this.signals = signals;
        this.planPeople = planPeople;
        this.people = people;
        this.spaces = spaces;
    }

    public Map<Long, Responses> of(long viewerId, List<Moment> moments, LocalDateTime now) {
        Map<Long, Responses> out = new HashMap<>();
        if (moments.isEmpty()) return out;
        List<Long> sayIds = moments.stream().filter(m -> m.getKind() == Kind.SAY).map(Moment::getId).toList();
        List<Long> planIds = moments.stream().filter(m -> m.getKind() == Kind.WHOS_IN).map(Moment::getId).toList();
        Map<Long, List<MomentSignal>> same = group(sayIds.isEmpty() ? List.of()
                : signals.findByKindAndMomentIdIn(SignalKind.SAME, sayIds));
        Map<Long, List<MomentSignal>> sayMore = group(sayIds.isEmpty() ? List.of()
                : signals.findByKindAndMomentIdIn(SignalKind.SAY_MORE, sayIds));
        Map<Long, List<PlanPerson>> plans = planIds.isEmpty() ? Map.of() : planPeople.findByMomentIdIn(planIds).stream()
                .collect(Collectors.groupingBy(PlanPerson::getMomentId));
        Map<Long, PlanSpaces.Space> spaceOf = spaces.forViewer(viewerId, moments, now);
        Relationships.Viewer viewer = moments.stream().anyMatch(m -> m.getAuthorId() == viewerId)
                ? people.viewer(viewerId) : null;

        for (Moment m : moments) {
            boolean mine = m.getAuthorId() == viewerId;
            boolean live = m.isLive(now) && m.getModeration().visibleToOthers();
            SameView sameView = null;
            SayMoreView sayMoreView = null;
            PlanView planView = null;
            ForAuthor forAuthor = null;
            List<MomentSignal> sameHere = same.getOrDefault(m.getId(), List.of());
            List<MomentSignal> moreHere = sayMore.getOrDefault(m.getId(), List.of());
            List<PlanPerson> planHere = plans.getOrDefault(m.getId(), List.of());
            if (m.getKind() == Kind.SAY && !mine) {
                boolean said = sameHere.stream().anyMatch(s -> s.getUserId() == viewerId);
                ResponsePolicy.Band band = ResponsePolicy.band(sameHere.size() - (said ? 1 : 0));
                sameView = new SameView(said, band == null ? null : band.name());
                sayMoreView = new SayMoreView(moreHere.stream().anyMatch(s -> s.getUserId() == viewerId));
            }
            PlanSpaces.Space space = spaceOf.get(m.getId());
            if (m.getKind() == Kind.WHOS_IN) {
                String you = mine ? "AUTHOR" : planHere.stream().filter(p -> p.getUserId() == viewerId).findFirst()
                        .map(p -> p.getState().name()).orElse("NONE");
                boolean open = ResponsePolicy.asksOpen(live, m.isCalledOff(), m.getPlanAt(), now);
                planView = new PlanView(you, open, m.isCalledOff(), space == null ? null : space.planId());
            }
            if (mine) {
                List<Long> asking = planHere.stream().filter(p -> p.getState() == PlanState.ASKED && !p.isSetAside())
                        .sorted(Comparator.comparing(PlanPerson::getAskedAt)).map(PlanPerson::getUserId).toList();
                List<Long> in = planHere.stream().filter(p -> p.getState() == PlanState.IN)
                        .sorted(Comparator.comparing(PlanPerson::getAnsweredAt)).map(PlanPerson::getUserId).toList();
                forAuthor = forAuthor(viewer, newestFirst(sameHere), newestFirst(moreHere), asking, in);
            }
            More more = m.getMoreText() == null ? null : more(m, mine, now);
            out.put(m.getId(), new Responses(sameView, sayMoreView, more, planView,
                    space == null ? null : space.canReply(), space == null ? null : space.replyId(), forAuthor));
        }
        return out;
    }

    private ForAuthor forAuthor(Relationships.Viewer viewer, List<Long> same, List<Long> sayMore, List<Long> asking,
                                List<Long> in) {
        List<Long> sameSeen = people.visible(viewer, same);
        List<Long> moreSeen = people.visible(viewer, sayMore);
        List<Long> askingSeen = people.visible(viewer, asking);
        List<Long> inSeen = people.visible(viewer, in);
        return new ForAuthor(people.names(first(sameSeen)), sameSeen.size() > NAMES_IN_A_LINE,
                people.names(first(moreSeen)), moreSeen.size() > NAMES_IN_A_LINE,
                people.names(first(askingSeen)), askingSeen.size() > NAMES_IN_A_LINE,
                people.names(first(inSeen)), inSeen.size() > NAMES_IN_A_LINE);
    }

    private static More more(Moment m, boolean mine, LocalDateTime now) {
        MomentTimes.Rough rough = MomentTimes.rough(m.getMoreAt(), now);
        return new More(m.getMoreText(), new When(rough.day().name(), rough.part() == null ? null : rough.part().name()),
                mine ? m.getMoreAt().toInstant(ZoneOffset.UTC) : null);
    }

    private static List<Long> newestFirst(List<MomentSignal> list) {
        return list.stream().sorted(Comparator.comparing(MomentSignal::getCreatedAt).reversed()
                .thenComparing(MomentSignal::getId, Comparator.reverseOrder())).map(MomentSignal::getUserId).toList();
    }

    private static List<Long> first(List<Long> ids) {
        return ids.size() <= NAMES_IN_A_LINE ? ids : new ArrayList<>(ids.subList(0, NAMES_IN_A_LINE));
    }

    private static Map<Long, List<MomentSignal>> group(List<MomentSignal> list) {
        return list.stream().collect(Collectors.groupingBy(MomentSignal::getMomentId));
    }
}
