package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.social.dto.ConversationDtos.ConversationView;
import com.safesphere.backend.social.dto.ConversationDtos.MessageView;
import com.safesphere.backend.social.dto.ConversationDtos.MomentRef;
import com.safesphere.backend.social.dto.ConversationDtos.You;
import com.safesphere.backend.social.dto.MomentDtos.Notice;
import com.safesphere.backend.social.dto.MomentDtos.Person;
import com.safesphere.backend.social.dto.MomentDtos.Plan;
import com.safesphere.backend.social.entity.ContentModeration.ModerationReason;
import com.safesphere.backend.social.entity.ContentModeration.ModerationSource;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import com.safesphere.backend.social.entity.Conversation;
import com.safesphere.backend.social.entity.Message;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import com.safesphere.backend.social.entity.ResponseEnums.MessageEvent;
import com.safesphere.backend.social.entity.ResponseEnums.MessageKind;
import com.safesphere.backend.social.entity.ResponseEnums.PlanState;
import com.safesphere.backend.social.entity.ResponseEnums.PrivateReplies;
import com.safesphere.backend.social.entity.SocialSettings;
import com.safesphere.backend.common.event.Tell;
import com.safesphere.backend.social.event.MessageSent;
import com.safesphere.backend.social.policy.ConversationPolicy;
import com.safesphere.backend.social.policy.ConversationPolicy.Turn;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.policy.ResponsePolicy;
import com.safesphere.backend.social.policy.Visibility;
import com.safesphere.backend.social.repository.ConversationRepository;
import com.safesphere.backend.social.repository.MessageRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.social.repository.PlanPersonRepository;
import com.safesphere.backend.social.repository.SocialSettingsRepository;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase N5 (N-91, N-92, N-94): private replies and plans. A conversation is seen only by its members (a REPLY: the author
 * and the replier; a PLAN: the author and the people IN), and only while the one rule lets them see each other
 * (Visibility.canSeeSpace): a block between the two people of a REPLY ends it for both, at once and silently. Exact times
 * are given to members only. Nothing records who read what.
 */
@Service
public class ConversationService {

    /** The most messages one answer carries (a plan's thread is small; a conversation closes within two days). */
    static final int MAX_MESSAGES = 300;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MomentRepository moments;
    private final PlanPersonRepository planPeople;
    private final SocialSettingsRepository settings;
    private final UserRepository users;
    private final MomentService momentService;
    private final Relationships relationships;
    private final SocialAccess access;
    private final ResponseLimits limits;
    private final PeopleLookup people;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
                               MomentRepository moments, PlanPersonRepository planPeople,
                               SocialSettingsRepository settings, UserRepository users, MomentService momentService,
                               Relationships relationships, SocialAccess access, ResponseLimits limits,
                               PeopleLookup people, ApplicationEventPublisher events, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.moments = moments;
        this.planPeople = planPeople;
        this.settings = settings;
        this.users = users;
        this.momentService = momentService;
        this.relationships = relationships;
        this.access = access;
        this.limits = limits;
        this.people = people;
        this.events = events;
        this.clock = clock;
    }

    /**
     * N-91: a private reply to the author of a moment that is on Today, if the author's setting lets the replier in. One
     * conversation per moment and person; the replier's first words wait for the author's answer (strict turns).
     */
    @Transactional
    public ConversationView startReply(long userId, String momentId, String rawText) {
        access.requireCanRespond(userId);
        MembershipGate.Member member = access.requireMembership(userId);
        LocalDateTime now = now();
        Moment seen = momentService.requireVisible(userId, momentId == null ? null : momentId.trim(), now);
        if (seen.getAuthorId() == userId) throw new InvalidRequestException(ResponsePolicy.OWN_MOMENT);
        if (!Visibility.canRespond(relationships.momentFacts(userId, seen, now))) {
            throw new ConflictException(ResponsePolicy.NOT_ON_TODAY);
        }
        Moment moment = moments.lockById(seen.getId()).orElseThrow(); // one start at a time per moment
        if (conversations.findByMomentIdAndReplierId(moment.getId(), userId).isPresent()) {
            throw new ConflictException(ConversationPolicy.ALREADY_REPLIED);
        }
        boolean connected = relationships.connected(userId, moment.getAuthorId());
        if (!ConversationPolicy.allows(settingOf(moment.getAuthorId()), connected)) {
            throw new ForbiddenException(ConversationPolicy.REPLIES_NOT_TAKEN);
        }
        boolean newMember = MomentPolicy.isNewMember(member.verifiedAt(), now);
        String text = cleanText(rawText, newMember);
        limits.startReply(userId, newMember);
        Conversation conversation = conversations.saveAndFlush(Conversation.reply(moment.getId(), userId, now,
                ResponsePolicy.replyClosesAt(moment.getExpiresAt())));
        Message message = messages.saveAndFlush(Message.text(conversation.getId(), userId, text, now));
        MessageSent sent = new MessageSent(message.getId(), userId, newMember && !connected, text);
        events.publishEvent(sent);
        messages.flush();
        tellAbout(userId, conversation, moment, messages.findById(message.getId()).orElse(message));
        return view(userId, conversation, moment, now, sent.supportSuggested() ? message.getId() : null);
    }

    /** Words in a conversation the caller belongs to: a REPLY takes turns, a PLAN does not; both only while open. */
    @Transactional
    public MessageView send(long userId, String conversationId, String rawText) {
        access.requireCanRespond(userId);
        MembershipGate.Member member = access.requireMembership(userId);
        LocalDateTime now = now();
        Conversation conversation = conversationId == null ? null
                : conversations.lockByPublicId(conversationId.trim()).orElse(null);
        Moment moment = requireMember(userId, conversation, now);
        if (!conversation.isOpen(now)) throw new ConflictException(ConversationPolicy.CLOSED);
        if (!ConversationPolicy.canSend(turnOf(userId, conversation, now))) {
            throw new ConflictException(ConversationPolicy.NOT_YOUR_TURN);
        }
        String text = cleanText(rawText, MomentPolicy.isNewMember(member.verifiedAt(), now));
        limits.message(userId);
        Message message = messages.saveAndFlush(Message.text(conversation.getId(), userId, text, now));
        MessageSent sent = new MessageSent(message.getId(), userId, false, text);
        events.publishEvent(sent);
        messages.flush();
        Message saved = messages.findById(message.getId()).orElse(message);
        tellAbout(userId, conversation, moment, saved);
        MessageView answer = messageViews(userId, List.of(saved), moment, now).get(0);
        return sent.supportSuggested() ? answer.withSupport() : answer;
    }

    /**
     * N7 (D29, D30): who should hear about these words, once the text check let them through (a held or removed message
     * tells nobody: no push ever precedes visibility). A private reply: the other person (it is their turn). A plan: the
     * author and everyone in, except the writer and anyone who blocked them or whom they blocked. The push package
     * listens after commit; social does not know it exists.
     */
    private void tellAbout(long writerId, Conversation conversation, Moment moment, Message message) {
        if (!message.getModeration().visibleToOthers()) return;
        if (conversation.getKind() == ConversationKind.REPLY) {
            long other = writerId == conversation.getReplierId() ? moment.getAuthorId() : conversation.getReplierId();
            events.publishEvent(Tell.to(Tell.Kind.REPLY_TURN, other, conversation.getPublicId()));
            return;
        }
        events.publishEvent(Tell.to(Tell.Kind.PLAN_MESSAGE, planAudience(moment, writerId), conversation.getPublicId()));
    }

    /** The author and the people in a plan, without [writerId] and without anyone who cannot see them (a block). */
    List<Long> planAudience(Moment moment, long writerId) {
        List<Long> out = new ArrayList<>();
        if (moment.getAuthorId() != writerId) out.add(moment.getAuthorId());
        for (var row : planPeople.findByMomentIdAndStateOrderByAnsweredAtAscIdAsc(moment.getId(), PlanState.IN)) {
            if (row.getUserId() != writerId) out.add(row.getUserId());
        }
        out.removeIf(id -> !relationships.canSeeProfile(id, writerId));
        return out;
    }

    /** One conversation, for one of its members (else: not found, exactly like one that does not exist). */
    @Transactional(readOnly = true)
    public ConversationView get(long userId, String conversationId) {
        access.requireMember(userId);
        LocalDateTime now = now();
        Conversation conversation = conversationId == null ? null
                : conversations.findByPublicId(conversationId.trim()).orElse(null);
        Moment moment = requireMember(userId, conversation, now);
        return view(userId, conversation, moment, now, null);
    }

    /** N-91: either of the two ends a private reply early; it becomes read-only for both. Never stopped by a pause. */
    @Transactional
    public void end(long userId, String conversationId) {
        access.requireMember(userId);
        LocalDateTime now = now();
        Conversation conversation = conversationId == null ? null
                : conversations.lockByPublicId(conversationId.trim()).orElse(null);
        requireMember(userId, conversation, now);
        if (conversation.getKind() != ConversationKind.REPLY) {
            throw new InvalidRequestException("Only a private reply can be ended");
        }
        if (conversation.getEndedAt() != null) return;
        conversation.end(now);
        conversations.saveAndFlush(conversation);
        messages.saveAndFlush(Message.system(conversation.getId(), userId, MessageEvent.ENDED, now));
    }

    /**
     * Phase N5 (for reports): the words [messageId] as [userId] sees them now: a TEXT message in a conversation they
     * belong to, written by someone they can see and not hidden from them. Anything else is not found, like a message
     * that does not exist.
     */
    @Transactional(readOnly = true)
    public Message requireVisibleMessage(long userId, String messageId) {
        LocalDateTime now = now();
        Message message = messageId == null ? null : messages.findByPublicId(messageId.trim()).orElse(null);
        if (message == null || message.getKind() != MessageKind.TEXT) throw messageNotFound();
        Conversation conversation = conversations.findById(message.getConversationId()).orElse(null);
        try {
            requireMember(userId, conversation, now);
        } catch (ResourceNotFoundException e) {
            throw messageNotFound();
        }
        if (message.getAuthorId() != userId) {
            if (!message.getModeration().visibleToOthers()) throw messageNotFound();
            if (people.visible(people.viewer(userId), List.of(message.getAuthorId())).isEmpty()) throw messageNotFound();
        }
        return message;
    }

    private static ResourceNotFoundException messageNotFound() {
        return new ResourceNotFoundException("Message not found");
    }

    // ---- settings (N-91) ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PrivateReplies privateReplies(long userId) {
        access.requireMember(userId);
        return settingOf(userId);
    }

    @Transactional
    public PrivateReplies setPrivateReplies(long userId, String raw) {
        access.requireMember(userId);
        PrivateReplies value;
        try {
            value = PrivateReplies.valueOf(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Choose who may reply privately");
        }
        LocalDateTime now = now();
        SocialSettings row = settings.findById(userId).orElse(null);
        if (row == null) row = new SocialSettings(userId, value, now);
        else row.privateReplies(value, now);
        settings.saveAndFlush(row);
        return value;
    }

    PrivateReplies settingOf(long userId) {
        return settings.findById(userId).map(SocialSettings::getPrivateReplies).orElse(PrivateReplies.MY_PEOPLE);
    }

    // ---- reads --------------------------------------------------------------------------------------------------------

    /**
     * The moment of [conversation] if [userId] belongs to it and may see it now; else 404. A REPLY also needs the two
     * people to see each other (a block either way, a membership that ended: the conversation is gone for both).
     */
    Moment requireMember(long userId, Conversation conversation, LocalDateTime now) {
        Moment moment = conversation == null ? null : moments.findById(conversation.getMomentId()).orElse(null);
        if (moment == null || !isMember(userId, conversation, moment)) throw notFound();
        if (!Visibility.canSeeSpace(relationships.momentFacts(userId, moment, now), true)) throw notFound();
        if (conversation.getKind() == ConversationKind.REPLY) {
            long other = userId == moment.getAuthorId() ? conversation.getReplierId() : moment.getAuthorId();
            if (!relationships.canSeeProfile(userId, other)) throw notFound();
        }
        return moment;
    }

    boolean isMember(long userId, Conversation conversation, Moment moment) {
        if (moment.getAuthorId() == userId) return true;
        if (conversation.getKind() == ConversationKind.REPLY) return conversation.getReplierId() == userId;
        return planPeople.existsByMomentIdAndUserIdAndState(moment.getId(), userId, PlanState.IN);
    }

    private Turn turnOf(long userId, Conversation conversation, LocalDateTime now) {
        List<Message> last = messages.findByConversationIdAndKindOrderByCreatedAtDescIdDesc(conversation.getId(),
                MessageKind.TEXT, PageRequest.of(0, 1));
        return ConversationPolicy.turn(conversation.getKind(), conversation.isOpen(now), conversation.getEndedAt() != null,
                last.isEmpty() ? null : last.get(0).getAuthorId(), userId);
    }

    private ConversationView view(long userId, Conversation conversation, Moment moment, LocalDateTime now,
                                  Long supportFor) {
        Relationships.Viewer viewer = people.viewer(userId);
        List<Person> members;
        if (conversation.getKind() == ConversationKind.REPLY) {
            long other = userId == moment.getAuthorId() ? conversation.getReplierId() : moment.getAuthorId();
            members = people.people(viewer, people.visible(viewer, List.of(other)), Map.of());
        } else {
            List<Long> ids = new ArrayList<>();
            ids.add(moment.getAuthorId());
            planPeople.findByMomentIdAndStateOrderByAnsweredAtAscIdAsc(moment.getId(), PlanState.IN)
                    .forEach(p -> ids.add(p.getUserId()));
            members = people.people(viewer, people.visible(viewer, ids), Map.of());
        }
        List<Message> all = messages.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
        if (all.size() > MAX_MESSAGES) all = all.subList(all.size() - MAX_MESSAGES, all.size());
        List<MessageView> shown = new ArrayList<>(messageViews(userId, all, moment, now));
        if (supportFor != null) {
            String id = all.stream().filter(m -> m.getId().equals(supportFor)).map(Message::getPublicId).findFirst()
                    .orElse(null);
            shown.replaceAll(m -> m.id().equals(id) ? m.withSupport() : m);
        }
        Turn turn = turnOf(userId, conversation, now);
        boolean paused = access.pauseOf(userId).filter(s -> s.getRestriction().stopsComments()).isPresent();
        User author = users.findById(moment.getAuthorId()).orElse(null);
        Plan plan = moment.getKind() != Kind.WHOS_IN ? null
                : new Plan(moment.getPlanAt() == null ? null : moment.getPlanAt().toInstant(ZoneOffset.UTC),
                        moment.getPlanWhere());
        MomentRef ref = new MomentRef(moment.getPublicId(), moment.getKind().name(), moment.getBody(), plan,
                moment.isCalledOff(), moment.getAuthorId() == userId, author == null ? null : author.getName());
        return new ConversationView(conversation.getPublicId(), conversation.getKind().name(), ref, members, shown,
                new You(turn.name(), ConversationPolicy.canSend(turn) && !paused, paused),
                conversation.getClosesAt().toInstant(ZoneOffset.UTC), conversation.isOpen(now));
    }

    /**
     * The messages [userId] may see, as they may see them: their own always (with a notice when held, removed or
     * collapsed: no shadow states); someone else's only while the viewer can see that person and no rule or moderator
     * holds or removed it (collapsed ones behind a tap). Quiet lines only about people the viewer can see.
     */
    List<MessageView> messageViews(long userId, List<Message> list, Moment moment, LocalDateTime now) {
        Relationships.Viewer viewer = people.viewer(userId);
        Set<Long> writers = new HashSet<>();
        list.forEach(m -> writers.add(m.getAuthorId()));
        Set<Long> visible = new HashSet<>(people.visible(viewer, writers));
        Map<Long, Person> personOf = people.byId(viewer, List.copyOf(visible));
        List<MessageView> out = new ArrayList<>();
        for (Message m : list) {
            boolean mine = m.getAuthorId() == userId;
            if (!mine && !visible.contains(m.getAuthorId())) continue;
            if (!mine && !m.getModeration().visibleToOthers()) continue;
            boolean system = m.getKind() == MessageKind.SYSTEM;
            out.add(new MessageView(m.getPublicId(), m.getKind().name(), system ? null : m.getBody(),
                    system ? m.getEvent().name() : null, personOf.get(m.getAuthorId()), mine,
                    m.getCreatedAt().toInstant(ZoneOffset.UTC),
                    !mine && m.getModeration() == ModerationState.COLLAPSED, mine ? notice(m) : null, null));
        }
        return out;
    }

    /** What the writer is told about their own message (as for moments: N-78; reports alone change nothing visible). */
    static Notice notice(Message m) {
        ModerationSource source = m.getModerationSource();
        if (m.getModeration() == ModerationState.NONE || source == null) return null;
        if (source == ModerationSource.REPORTS) return new Notice(m.getModeration().name(), null, null, false, null);
        ModerationReason reason = m.getModerationReason();
        boolean system = source == ModerationSource.SYSTEM;
        return new Notice(m.getModeration().name(), reason == null ? null : reason.name(), source.name(),
                system && m.getModeration() == ModerationState.COLLAPSED, system ? null : m.getModerationDecision());
    }

    private static String cleanText(String raw, boolean newMember) {
        String text = MomentPolicy.cleanText(raw);
        int length = MomentPolicy.length(text);
        if (length == 0) throw new InvalidRequestException("Write something first");
        if (length > ResponsePolicy.TEXT_MAX) throw new InvalidRequestException("A message has at most 500 characters");
        if (newMember && MomentPolicy.containsLink(text)) {
            throw new InvalidRequestException(MomentPolicy.NO_LINKS_FOR_NEW_MEMBERS);
        }
        return text;
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Conversation not found");
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
