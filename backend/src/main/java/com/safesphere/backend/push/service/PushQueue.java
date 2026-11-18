package com.safesphere.backend.push.service;

import com.safesphere.backend.config.PushProperties;
import com.safesphere.backend.push.entity.PushDevice;
import com.safesphere.backend.push.entity.PushMessage;
import com.safesphere.backend.push.entity.PushType;
import com.safesphere.backend.push.repository.PushDeviceRepository;
import com.safesphere.backend.push.repository.PushMessageRepository;
import com.safesphere.backend.push.service.PushSender.Outcome;
import com.safesphere.backend.push.service.PushSender.Push;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N7 (D35 = N-127): the push queue in the database (the N6 pattern). Each step is its own short transaction, so the
 * provider is always called outside any transaction (Phase 14 rule) and a restart loses nothing: a row claimed before a
 * crash is tried again when its lease runs out.
 */
@Service
public class PushQueue {

    private final PushMessageRepository messages;
    private final PushDeviceRepository devices;
    private final PushProperties properties;

    public PushQueue(PushMessageRepository messages, PushDeviceRepository devices, PushProperties properties) {
        this.messages = messages;
        this.devices = devices;
        this.properties = properties;
    }

    /** A claimed message, ready for the provider. */
    public record Claimed(long messageId, long deviceId, Push push) {
    }

    /** One row per phone whose sign-in is running; the same event id on all of them. Returns how many phones. */
    @Transactional
    public int enqueue(long userId, PushType type, String subject, LocalDateTime notBefore, LocalDateTime now) {
        PushPolicy.requireSubject(subject);
        List<PushDevice> phones = devices.findActiveByUserId(userId, now);
        if (phones.isEmpty()) return 0;
        String eventId = UUID.randomUUID().toString();
        for (PushDevice phone : phones) {
            messages.save(new PushMessage(eventId, phone.getId(), userId, type, subject, notBefore, now));
        }
        return phones.size();
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds(LocalDateTime now) {
        return messages.findDueIds(now, PageRequest.of(0, 200));
    }

    /** Takes a due message; closes it instead when it expired or its phone's sign-in ended. */
    @Transactional
    public Optional<Claimed> claim(long id, LocalDateTime now) {
        PushMessage message = messages.findById(id).orElse(null);
        if (message == null || message.getStatus() != PushMessage.Status.QUEUED || message.getNextAttemptAt() == null
                || message.getNextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        if (!message.getExpiresAt().isAfter(now)) {
            message.close(PushMessage.Status.EXPIRED, "EXPIRED");
            return Optional.empty();
        }
        PushDevice device = devices.findById(message.getDeviceId()).orElse(null);
        if (device == null) {
            message.close(PushMessage.Status.SKIPPED, "NO_DEVICE");
            return Optional.empty();
        }
        if (!devices.isActive(device.getId(), now)) {
            message.close(PushMessage.Status.SKIPPED, "SESSION_ENDED");
            return Optional.empty();
        }
        message.claim(now.plus(PushPolicy.LEASE));
        Push push = new Push(device.getToken(), message.getType(), message.getSubject(), message.getEventId(),
                message.isHighPriority(), PushPolicy.ttlLeft(now, message.getExpiresAt()),
                PushPolicy.collapseKey(message.getType(), message.getSubject()));
        return Optional.of(new Claimed(message.getId(), device.getId(), push));
    }

    /** What the provider said. An invalid token removes the phone (its other waiting rows go with it). */
    @Transactional
    public void result(Claimed claimed, Outcome outcome, LocalDateTime now) {
        PushMessage message = messages.findById(claimed.messageId()).orElse(null);
        if (message == null) return;
        switch (outcome.result()) {
            case ACCEPTED -> message.sent(now);
            case FAILED -> message.close(PushMessage.Status.FAILED, "REFUSED");
            case UNREGISTERED -> {
                message.close(PushMessage.Status.FAILED, "UNREGISTERED");
                messages.flush();
                messages.deleteByDeviceIds(List.of(claimed.deviceId()));
                devices.deleteById(claimed.deviceId());
            }
            case RETRY, AUTH -> {
                LocalDateTime next = now.plus(PushPolicy.retryDelay(message.getAttempts(), properties.retryDelay(),
                        outcome.result() == PushSender.Result.AUTH ? PushPolicy.MAX_RETRY_DELAY : outcome.retryAfter()));
                if (message.getAttempts() >= properties.maxAttempts()) {
                    message.close(PushMessage.Status.FAILED, outcome.result() == PushSender.Result.AUTH ? "AUTH" : "GAVE_UP");
                } else if (!next.isBefore(message.getExpiresAt())) {
                    message.close(PushMessage.Status.EXPIRED, "EXPIRED");
                } else {
                    message.retryAt(next);
                }
            }
        }
    }
}
