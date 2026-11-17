package com.safesphere.backend.social.service;

import com.safesphere.backend.social.dto.ConversationDtos.MessageView;
import com.safesphere.backend.social.dto.MomentDtos.Person;
import com.safesphere.backend.social.dto.MomentDtos.Plan;
import com.safesphere.backend.social.dto.RepliesDtos.MomentBrief;
import com.safesphere.backend.social.dto.RepliesDtos.RepliesPage;
import com.safesphere.backend.social.dto.RepliesDtos.ReplyItem;
import com.safesphere.backend.social.entity.Conversation;
import com.safesphere.backend.social.entity.Message;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.MomentSignal;
import com.safesphere.backend.social.entity.PlanPerson;
import com.safesphere.backend.social.entity.RepliesOpened;
import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import com.safesphere.backend.social.entity.ResponseEnums.MessageKind;
import com.safesphere.backend.social.entity.ResponseEnums.PlanState;
import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
import com.safesphere.backend.social.policy.ConversationPolicy;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.policy.ResponsePolicy;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.ConversationRepository;
import com.safesphere.backend.social.repository.MessageRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.repository.MomentSignalRepository;
import com.safesphere.backend.social.repository.PlanPersonRepository;
import com.safesphere.backend.social.repository.RepliesOpenedRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase N5 (D19, N-93): Replies. Built from the person's own data each time it is asked for: nothing is stored about who
 * looked at what. The only state is the person's own "last opened Replies" time ([RepliesOpened]); [ReplyItem.fresh]
 * and the tab's dot compare with it. Same never lights the dot. Everything passes the one visibility rule: a line about
 * someone the person cannot see (a block either way, a membership that ended) is simply not there.
 */
@Service
public class RepliesService {

    /** The most lines per section (a campus day is small; everything ends within days). */
    static final int SECTION_MAX = 30;
    /** How far back Replies looks: response data lives 7 days after a moment's end (N-98). */
    static final java.time.Duration LOOK_BACK = MomentPolicy.PURGE_AFTER;
    static final int NAMES = 3;

    private final MomentRepository moments;
    private final MomentSignalRepository signals;
    private final PlanPersonRepository planPeople;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final RepliesOpenedRepository opened;
    private final UserRepository users;
    private final ConversationService conversationService;
    private final Relationships relationships;
    private final PeopleLookup people;
    private final SocialAccess access;
    private final ResponseLimits limits;
    private final Clock clock;

    public RepliesService(MomentRepository moments, MomentSignalRepository signals, PlanPersonRepository planPeople,
                          ConversationRepository conversations, MessageRepository messages,
                          RepliesOpenedRepository opened, UserRepository users, ConversationService conversationService,
                          Relationships relationships, PeopleLookup people, SocialAccess access, ResponseLimits limits,
                          Clock clock) {
        this.moments = moments;
        this.signals = signals;
        this.planPeople = planPeople;
        this.conversations = conversations;
        this.messages = messages;
        this.opened = opened;
        this.users = users;
        this.conversationService = conversationService;
        this.relationships = relationships;
        this.people = people;
        this.access = access;
        this.limits = limits;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RepliesPage page(long userId) {
        access.requireMember(userId);
        limits.read(userId);
        return build(userId, now());
    }

    /** The dot on the tab: something arrived since the person last opened Replies (D19). */
    @Transactional(readOnly = true)
    public boolean fresh(long userId) {
        access.requireMember(userId);
        limits.read(userId);
        return build(userId, now()).fresh();
    }

    /** The person opened Replies: their own time, the only thing stored (never per message, never shown to anyone). */
    @Transactional
    public void opened(long userId) {
        access.requireMember(userId);
        LocalDateTime now = now();
        RepliesOpened row = opened.findById(userId).orElse(null);
        if (row == null) row = new RepliesOpened(userId, now);
        else row.opened(now);
        opened.saveAndFlush(row);
    }

    // ---- assembly -------------------------------------------------------------------------------------------------------

    RepliesPage build(long userId, LocalDateTime now) {
        LocalDateTime last = opened.findById(userId).map(RepliesOpened::getOpenedAt).orElse(LocalDateTime.MIN);
        Relationships.Viewer viewer = people.viewer(userId);
        Context ctx = new Context(userId, viewer, now, last);

        List<Line> needsYou = new ArrayList<>();
        List<Line> plans = new ArrayList<>();
        List<Line> replies = new ArrayList<>();
        List<Line> same = new ArrayList<>();

        // 1. Your own moments: asks, Say more asks, Same, your plans.
        List<Moment> own = moments.authoredEndingAfter(userId, now.minus(LOOK_BACK));
        List<Long> ownIds = own.stream().map(Moment::getId).toList();
        Map<Long, List<PlanPerson>> asksOf = ownIds.isEmpty() ? Map.of() : planPeople.findByMomentIdIn(ownIds).stream()
                .collect(Collectors.groupingBy(PlanPerson::getMomentId));
        Map<Long, List<MomentSignal>> signalsOf = new HashMap<>();
        if (!ownIds.isEmpty()) {
            for (SignalKind kind : SignalKind.values()) {
                signals.findByKindAndMomentIdIn(kind, ownIds).forEach(s ->
                        signalsOf.computeIfAbsent(s.getMomentId(), k -> new ArrayList<>()).add(s));
            }
        }
        Map<Long, Conversation> planConversationOf = ownIds.isEmpty() ? Map.of()
                : conversations.findByMomentIdIn(ownIds).stream().filter(c -> c.getKind() == ConversationKind.PLAN)
                .collect(Collectors.toMap(Conversation::getMomentId, Function.identity()));
        for (Moment m : own) {
            MomentBrief brief = brief(m, ctx, true);
            boolean live = m.isLive(now) && m.getModeration().visibleToOthers();
            if (m.getKind() == Kind.WHOS_IN) {
                boolean asksOpen = ResponsePolicy.asksOpen(live, m.isCalledOff(), m.getPlanAt(), now);
                List<PlanPerson> asking = asksOf.getOrDefault(m.getId(), List.of()).stream()
                        .filter(p -> p.getState() == PlanState.ASKED && !p.isSetAside())
                        .sorted(Comparator.comparing(PlanPerson::getAskedAt).reversed()).toList();
                Names names = names(ctx, asking.stream().map(PlanPerson::getUserId).toList());
                if (asksOpen && !names.isEmpty()) {
                    boolean fresh = asking.stream().filter(p -> names.ids.contains(p.getUserId()))
                            .anyMatch(p -> p.getAskedAt().isAfter(last));
                    needsYou.add(new Line(item("ASKS", brief, null, names, null, true, null, fresh), sortKey(asking
                            .get(0).getAskedAt())));
                }
                Conversation plan = planConversationOf.get(m.getId());
                if (plan != null) plans.add(conversationLine("PLAN", ctx, plan, m, brief, null));
            } else {
                List<MomentSignal> list = signalsOf.getOrDefault(m.getId(), List.of());
                List<MomentSignal> asks = list.stream().filter(s -> s.getKind() == SignalKind.SAY_MORE)
                        .sorted(Comparator.comparing(MomentSignal::getCreatedAt).reversed()).toList();
                Names askNames = names(ctx, asks.stream().map(MomentSignal::getUserId).toList());
                if (live && m.getMoreText() == null && !askNames.isEmpty()) {
                    boolean fresh = asks.stream().filter(s -> askNames.ids.contains(s.getUserId()))
                            .anyMatch(s -> s.getCreatedAt().isAfter(last));
                    needsYou.add(new Line(item("SAY_MORE", brief, null, askNames, null, true, null, fresh),
                            sortKey(asks.get(0).getCreatedAt())));
                }
                List<MomentSignal> felt = list.stream().filter(s -> s.getKind() == SignalKind.SAME)
                        .sorted(Comparator.comparing(MomentSignal::getCreatedAt).reversed()).toList();
                Names sameNames = names(ctx, felt.stream().map(MomentSignal::getUserId).toList());
                if (live && !sameNames.isEmpty()) {
                    // D19: Same never lights the dot.
                    same.add(new Line(item("SAME", brief, null, sameNames, null, true, null, false),
                            sortKey(felt.get(0).getCreatedAt())));
                }
            }
        }

        // 2. Private replies, both sides.
        for (Conversation c : conversations.repliesOf(userId)) {
            Moment m = moments.findById(c.getMomentId()).orElse(null);
            if (m == null || !visible(ctx, c, m)) continue;
            Line line = conversationLine("REPLY", ctx, c, m, brief(m, ctx, m.getAuthorId() == userId), null);
            if (line == null) continue;
            if ("YOUR_TURN".equals(line.item.turn())) {
                needsYou.add(new Line(retype(line.item, "YOUR_TURN"), line.key));
            } else {
                replies.add(line);
            }
        }

        // 3. Your asks on other people's plans, and the plans you are in.
        for (PlanPerson p : planPeople.findByUserId(userId)) {
            Moment m = moments.findById(p.getMomentId()).orElse(null);
            if (m == null || m.getAuthorId() == userId) continue;
            if (!Visibility.canSeeSpace(relationships.momentFacts(userId, m, now), true)) continue;
            MomentBrief brief = brief(m, ctx, false);
            boolean live = m.isLive(now) && m.getModeration().visibleToOthers();
            switch (p.getState()) {
                case IN -> {
                    Conversation plan = conversations.findByMomentIdAndKind(m.getId(), ConversationKind.PLAN)
                            .orElse(null);
                    if (plan != null) plans.add(conversationLine("PLAN", ctx, plan, m, brief, p.getAnsweredAt()));
                }
                case ASKED -> {
                    String type;
                    boolean fresh = false;
                    LocalDateTime key = p.getAskedAt();
                    if (m.isCalledOff()) {
                        type = "CALLED_OFF";
                        fresh = m.getCalledOffAt().isAfter(last);
                        key = m.getCalledOffAt();
                    } else if (ResponsePolicy.asksOpen(live, false, m.getPlanAt(), now)) {
                        type = "WAITING";
                    } else {
                        type = "CLOSED"; // D16: never "declined"; it closed quietly
                    }
                    plans.add(new Line(item(type, brief, null, Names.NONE, null, "WAITING".equals(type), null, fresh),
                            sortKey(key)));
                }
                case REMOVED -> plans.add(new Line(item("NOT_IN", brief, null, Names.NONE, null, false, null,
                        p.getAnsweredAt() != null && p.getAnsweredAt().isAfter(last)), sortKey(p.getAnsweredAt())));
            }
        }

        // 4. Follow-ups you asked for (D15: askers are told in Replies).
        for (MomentSignal s : signals.findByUserIdAndKind(userId, SignalKind.SAY_MORE)) {
            Moment m = moments.findById(s.getMomentId()).orElse(null);
            if (m == null || m.getMoreText() == null) continue;
            if (!Visibility.canSeeMoment(relationships.momentFacts(userId, m, now))) continue;
            needsYou.add(new Line(item("MORE", brief(m, ctx, false), null, Names.NONE, null, false, null,
                    m.getMoreAt().isAfter(last)), sortKey(m.getMoreAt())));
        }

        List<ReplyItem> a = sorted(needsYou);
        List<ReplyItem> b = sorted(plans);
        List<ReplyItem> c = sorted(replies);
        List<ReplyItem> d = sorted(same);
        boolean fresh = a.stream().anyMatch(ReplyItem::fresh) || b.stream().anyMatch(ReplyItem::fresh)
                || c.stream().anyMatch(ReplyItem::fresh);
        return new RepliesPage(a, b, c, d, fresh);
    }

    /**
     * A plan or a private reply as one line, or null when the viewer has nothing to see in it yet (the author of a
     * private reply whose first words still wait for the check). [joinedAt] = when the viewer was added to someone
     * else's plan (being added is news).
     */
    private Line conversationLine(String type, Context ctx, Conversation c, Moment m, MomentBrief brief,
                                  LocalDateTime joinedAt) {
        List<Message> all = messages.findByConversationIdOrderByCreatedAtAscIdAsc(c.getId());
        List<MessageView> shown = conversationService.messageViews(ctx.userId, all, m, ctx.now);
        if (type.equals("REPLY") && shown.stream().noneMatch(v -> "TEXT".equals(v.kind()))) return null;
        Map<String, Message> byPublicId = all.stream().collect(Collectors.toMap(Message::getPublicId, x -> x));
        LocalDateTime lastAt = c.getCreatedAt();
        boolean fresh = joinedAt != null && joinedAt.isAfter(ctx.last);
        Long lastWriter = null;
        for (MessageView v : shown) {
            Message msg = byPublicId.get(v.id());
            if (msg.getCreatedAt().isAfter(lastAt)) lastAt = msg.getCreatedAt();
            if (!v.mine() && msg.getCreatedAt().isAfter(ctx.last)) fresh = true;
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).getKind() == MessageKind.TEXT) {
                lastWriter = all.get(i).getAuthorId();
                break;
            }
        }
        ConversationPolicy.Turn turn = ConversationPolicy.turn(c.getKind(), c.isOpen(ctx.now), c.getEndedAt() != null,
                lastWriter, ctx.userId);
        Names who = Names.NONE;
        if (c.getKind() == ConversationKind.REPLY) {
            long other = ctx.userId == m.getAuthorId() ? c.getReplierId() : m.getAuthorId();
            who = names(ctx, List.of(other));
        }
        return new Line(item(type, brief, c.getPublicId(), who, turn.name(), c.isOpen(ctx.now),
                lastAt.toInstant(ZoneOffset.UTC), fresh), sortKey(lastAt));
    }

    private boolean visible(Context ctx, Conversation c, Moment m) {
        try {
            conversationService.requireMember(ctx.userId, c, ctx.now);
            return true;
        } catch (com.safesphere.backend.common.exception.ResourceNotFoundException e) {
            return false;
        }
    }

    private MomentBrief brief(Moment m, Context ctx, boolean mine) {
        Plan plan = m.getKind() != Kind.WHOS_IN ? null
                : new Plan(m.getPlanAt() == null ? null : m.getPlanAt().toInstant(ZoneOffset.UTC), m.getPlanWhere());
        String author = mine ? null : users.findById(m.getAuthorId()).map(User::getName).orElse(null);
        return new MomentBrief(m.getPublicId(), m.getKind().name(), m.getBody(), plan, m.isCalledOff(), mine, author,
                m.isLive(ctx.now) && m.getModeration().visibleToOthers());
    }

    /** At most 3 people the viewer may see, in the given order, and whether there are more. */
    private Names names(Context ctx, List<Long> ids) {
        List<Long> visible = people.visible(ctx.viewer, ids);
        if (visible.isEmpty()) return Names.NONE;
        List<Long> shown = visible.size() > NAMES ? visible.subList(0, NAMES) : visible;
        return new Names(people.people(ctx.viewer, shown, Map.of()), visible.size() > NAMES, visible);
    }

    private static ReplyItem item(String type, MomentBrief brief, String conversationId, Names names, String turn,
                                  boolean open, Instant at, boolean fresh) {
        return new ReplyItem(type, brief, conversationId, names.people, names.more, turn, open, at, fresh);
    }

    private static ReplyItem retype(ReplyItem i, String type) {
        return new ReplyItem(type, i.moment(), i.conversationId(), i.people(), i.morePeople(), i.turn(), i.open(),
                i.at(), i.fresh());
    }

    private static List<ReplyItem> sorted(List<Line> lines) {
        return lines.stream().sorted(Comparator.comparing((Line l) -> l.key).reversed()).limit(SECTION_MAX)
                .map(l -> l.item).toList();
    }

    private static LocalDateTime sortKey(LocalDateTime at) {
        return Optional.ofNullable(at).orElse(LocalDateTime.MIN);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private record Context(long userId, Relationships.Viewer viewer, LocalDateTime now, LocalDateTime last) {
    }

    private record Line(ReplyItem item, LocalDateTime key) {
    }

    private record Names(List<Person> people, boolean more, List<Long> ids) {
        static final Names NONE = new Names(List.of(), false, List.of());

        boolean isEmpty() {
            return people.isEmpty();
        }
    }
}
