package com.safesphere.backend.push.service;

import com.safesphere.backend.config.PushProperties;
import com.safesphere.backend.push.entity.PushType;
import com.safesphere.backend.push.repository.PushMessageRepository;
import com.safesphere.backend.push.repository.PushSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * N7 (D26-D31 = N-118 to N-123): THE way to send a push. Producers (B2/B3 listeners, AFTER their event committed) call
 * [notify]; this applies the person's switch, the social caps and batching, then queues one pointer per running sign-in
 * and wakes the dispatcher after commit. With PUSH_PROVIDER=NONE nothing is queued. Never throws into its caller's work:
 * a push is never a reason for something else to fail.
 */
@Service
public class PushService {

    private final PushQueue queue;
    private final PushMessageRepository messages;
    private final PushSettingRepository settings;
    private final PushDispatcher dispatcher;
    private final PushProperties properties;
    private final Clock clock;

    public PushService(PushQueue queue, PushMessageRepository messages, PushSettingRepository settings,
                       PushDispatcher dispatcher, PushProperties properties, Clock clock) {
        this.queue = queue;
        this.messages = messages;
        this.settings = settings;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    /** Queue a push now (or after the batch window for kinds that batch). True when at least one phone gets it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean notify(long userId, PushType type, String subject) {
        return queueFor(userId, type, subject, null);
    }

    /** Queue a push for a later moment (On my way's due time and nudge). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean notifyAt(long userId, PushType type, String subject, Instant when) {
        return queueFor(userId, type, subject, LocalDateTime.ofInstant(when, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
    }

    /** Pushes of this kind about this subject that have not gone out yet are no longer wanted (a walk ended, ...). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int forget(long userId, PushType type, String subject) {
        return messages.deleteQueued(userId, type, subject);
    }

    /** Was this person told (queued or sent) about this subject after a time? */
    @Transactional(readOnly = true)
    public boolean toldSince(long userId, PushType type, String subject, Instant since) {
        return messages.existsSince(userId, type, subject, LocalDateTime.ofInstant(since, ZoneOffset.UTC));
    }

    private boolean queueFor(long userId, PushType type, String subject, LocalDateTime when) {
        if (properties.providerKind() == PushProperties.Provider.NONE) return false;
        PushPolicy.requireSubject(subject);
        if (!PushPolicy.switchedOn(type, settings.findById(userId).orElse(null))) return false;
        LocalDateTime now = now();
        if (type.group() == PushType.Group.SOCIAL) {
            long lastHour = messages.countEventsSince(userId, PushPolicy.SOCIAL, now.minus(Duration.ofHours(1)));
            long lastDay = messages.countEventsSince(userId, PushPolicy.SOCIAL, now.minus(Duration.ofDays(1)));
            if (PushPolicy.capReached(lastHour, lastDay, properties.socialPerHour(), properties.socialPerDay())) return false;
        }
        if (PushPolicy.batches(type) && messages.existsQueued(userId, type, subject)) return false;
        LocalDateTime notBefore = when != null ? when : PushPolicy.notBefore(type, now, properties.batchWindow());
        int phones = queue.enqueue(userId, type, subject, notBefore, now);
        if (phones > 0) kickAfterCommit();
        return phones > 0;
    }

    private void kickAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.kick();
                }
            });
        } else {
            dispatcher.kick();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
