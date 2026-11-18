package com.safesphere.backend.push.service;

import com.safesphere.backend.config.PushProperties;
import com.safesphere.backend.push.repository.PushDeviceRepository;
import com.safesphere.backend.push.repository.PushMessageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * N7 (D28 = N-120): the push clean-up, hourly. Deletes the phones of ended sign-ins (signed out, revoked, expired, erased)
 * and tokens nobody refreshed for [PushProperties#staleTokenAfter], and queue rows older than [PushProperties#retention].
 * Deletion only; logs counts.
 */
@Service
public class PushCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(PushCleanupJob.class);

    private final PushDeviceRepository devices;
    private final PushMessageRepository messages;
    private final PushProperties properties;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate inNewTransaction;

    public PushCleanupJob(PushDeviceRepository devices, PushMessageRepository messages, PushProperties properties,
                          Clock clock, org.springframework.transaction.PlatformTransactionManager transactions) {
        this.inNewTransaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        this.inNewTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.devices = devices;
        this.messages = messages;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.push.cleanup-interval:PT1H}", initialDelayString = "PT4M")
    public void scheduled() {
        if (!properties.jobEnabled()) return;
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("The push clean-up stopped ({}); the next run tries again", e.getClass().getSimpleName());
        }
    }

    /**
     * N7 (D28): a sign-in ended: its phone's token and queued pushes go at once (the hourly run would also find them;
     * the send step already refuses an ended sign-in). Its own transaction: it runs after the auth commit.
     */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(com.safesphere.backend.common.event.SessionsEnded event) {
        try {
            inNewTransaction.executeWithoutResult(status -> {
                LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                List<Long> ended = devices.findEnded(now, now.minus(properties.staleTokenAfter()));
                if (ended.isEmpty()) return;
                messages.deleteByDeviceIds(ended);
                devices.deleteAllByIdInBatch(ended);
            });
        } catch (RuntimeException e) {
            log.warn("Phones of ended sign-ins stay until the next clean-up ({})", e.getClass().getSimpleName());
        }
    }

    /** Returns {devices removed, queue rows removed}. */
    @Transactional
    public int[] run() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        List<Long> ended = devices.findEnded(now, now.minus(properties.staleTokenAfter()));
        if (!ended.isEmpty()) {
            messages.deleteByDeviceIds(ended);
            devices.deleteAllByIdInBatch(ended);
        }
        int old = messages.deleteCreatedBefore(now.minus(properties.retention()));
        if (!ended.isEmpty() || old > 0) log.info("Push clean-up: {} phones and {} queue rows removed", ended.size(), old);
        return new int[] {ended.size(), old};
    }
}
