package com.safesphere.backend.push.service;

import com.safesphere.backend.common.event.Tell;
import com.safesphere.backend.push.entity.PushType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * N7 (D29, D30 = N-121, N-122): turns a committed [Tell] into pushes. Runs AFTER COMMIT (a write that rolled back tells
 * nobody); each recipient separately; never throws back into anyone's work.
 *
 * "One push per plan until you open Replies" (D30): a plan message is pushed to a person only if nothing about that plan
 * reached them since they last opened Replies (their own replies_opened time: no per-message read state, N-93).
 */
@Component
public class PushProducer {

    private static final Logger log = LoggerFactory.getLogger(PushProducer.class);

    private final PushService push;
    private final JdbcTemplate jdbc;

    public PushProducer(PushService push, JdbcTemplate jdbc) {
        this.push = push;
        this.jdbc = jdbc;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(Tell tell) {
        PushType type = PushType.valueOf(tell.kind().name());
        for (Long recipient : new LinkedHashSet<>(tell.recipients())) {
            try {
                if (type == PushType.PLAN_MESSAGE && toldSinceRepliesOpened(recipient, tell.subject())) continue;
                push.notify(recipient, type, tell.subject());
            } catch (RuntimeException e) {
                log.warn("A push could not be queued ({})", e.getClass().getSimpleName());
            }
        }
    }

    private boolean toldSinceRepliesOpened(long userId, String subject) {
        List<Timestamp> opened = jdbc.queryForList("SELECT opened_at FROM replies_opened WHERE user_id = ?",
                Timestamp.class, userId);
        Instant since = opened.isEmpty() || opened.get(0) == null ? Instant.EPOCH
                : opened.get(0).toLocalDateTime().toInstant(ZoneOffset.UTC);
        return push.toldSince(userId, PushType.PLAN_MESSAGE, subject, since);
    }
}
