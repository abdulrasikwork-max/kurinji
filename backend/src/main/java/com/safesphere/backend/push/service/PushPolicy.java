package com.safesphere.backend.push.service;

import com.safesphere.backend.push.entity.PushSetting;
import com.safesphere.backend.push.entity.PushType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * N7 (D29-D31, D35 = N-121 to N-123, N-127): the pure rules of the push queue. Which kinds a person's switches let through,
 * the anti-noise rules (batching, caps), the collapse key, the retry delay. No I/O; PushPolicyTest covers every rule.
 */
public final class PushPolicy {

    /** An opaque id: a UUID, a number or a short code. Never anything a person wrote. */
    public static final Pattern SUBJECT = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    /** An FCM registration token as Android hands it out. */
    public static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_:\\-]{32,512}$");

    /** A claimed message is held this long before another run may try it again (a crash mid-send). */
    public static final Duration LEASE = Duration.ofMinutes(2);

    /** The longest the queue waits between two attempts, whatever the provider says. */
    public static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(10);

    /** Kinds that count against the per-person caps (D30). */
    public static final Set<PushType> SOCIAL = EnumSet.copyOf(java.util.Arrays.stream(PushType.values())
            .filter(t -> t.group() == PushType.Group.SOCIAL).toList());

    private PushPolicy() {
    }

    /** No settings row = the defaults: everything on except the staff summary. */
    public static boolean switchedOn(PushType type, PushSetting setting) {
        if (setting == null) return type.toggle() != PushType.Switch.STAFF;
        return setting.isOn(type.toggle());
    }

    /** "I'm in" within the batch window become one push (the phone then names everyone who is in). */
    public static boolean batches(PushType type) {
        return type == PushType.PLAN_ASK;
    }

    public static LocalDateTime notBefore(PushType type, LocalDateTime now, Duration batchWindow) {
        return batches(type) ? now.plus(batchWindow) : now;
    }

    public static boolean capReached(long eventsLastHour, long eventsLastDay, int perHour, int perDay) {
        return eventsLastHour >= perHour || eventsLastDay >= perDay;
    }

    /** One thread (a plan, a conversation, an SOS, a walk) keeps only its latest undelivered push at FCM. */
    public static String collapseKey(PushType type, String subject) {
        return type.thread() + "-" + subject;
    }

    /** The provider's hint when it gave one, otherwise the base delay times the attempt; never more than 10 minutes. */
    public static Duration retryDelay(int attempt, Duration base, Duration hint) {
        Duration delay = hint != null ? hint : base.multipliedBy(Math.max(1, attempt));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    /** What is left of a message's lifetime, in whole seconds, at least one (FCM drops it after that). */
    public static long ttlLeft(LocalDateTime now, LocalDateTime expiresAt) {
        return Math.max(1, Duration.between(now, expiresAt).toSeconds());
    }

    public static void requireSubject(String subject) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new IllegalArgumentException("a push subject is an opaque id");
        }
    }
}
