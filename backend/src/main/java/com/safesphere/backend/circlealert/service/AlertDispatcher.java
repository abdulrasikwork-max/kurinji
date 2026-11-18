package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.circlealert.service.AlertQueue.Outgoing;
import com.safesphere.backend.common.sms.SmsGateway;
import com.safesphere.backend.common.sms.SmsGateway.SmsRefusedException;
import com.safesphere.backend.config.AlertProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * N6 (D20): works the alert queue every few seconds ([AlertProperties#dispatchInterval], 5 s) and at once when an SOS
 * starts, gets its first location or ends ([kick]). The queue lives in the database, so it survives a restart.
 *
 * One run: plan (one transaction), then for each due text: claim (one transaction) → the provider (NO transaction) →
 * the result (one transaction). Only one run at a time ([lock]); a kick while one runs is picked up by the next tick.
 * Turned off with app.alerts.job-enabled=false (the tests call [runOnce]). Logs counts and the provider's status
 * code only: never a number, a text or a location.
 */
@Service
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final AlertQueue queue;
    private final SmsGateway gateway;
    private final AlertProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean kickPending = new AtomicBoolean();
    private final ExecutorService kicker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "alert-kick");
        thread.setDaemon(true);
        return thread;
    });

    public AlertDispatcher(AlertQueue queue, SmsGateway gateway, AlertProperties properties, Clock clock) {
        this.queue = queue;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.alerts.dispatch-interval:PT5S}", initialDelayString = "PT15S")
    public void scheduled() {
        if (properties.jobEnabled()) safeRun();
    }

    /** Something changed for an SOS: work the queue now instead of at the next tick (asynchronous, never blocks). */
    public void kick() {
        if (!properties.jobEnabled()) return;
        if (kickPending.compareAndSet(false, true)) {
            kicker.execute(() -> {
                kickPending.set(false);
                safeRun();
            });
        }
    }

    /** One run. Returns how many texts the provider accepted (for tests and the log). */
    public int runOnce() {
        if (!lock.tryLock()) return 0;
        try {
            queue.plan(now());
            int accepted = 0;
            int refused = 0;
            for (Long id : queue.dueIds(now())) {
                Optional<Outgoing> outgoing = queue.claim(id, now());
                if (outgoing.isEmpty()) continue;
                Outgoing text = outgoing.get();
                String reference;
                try {
                    reference = gateway.send(text.phoneE164(), text.text());
                } catch (SmsRefusedException e) {
                    refused++;
                    log.warn("A Safety Circle text was not accepted: {}", e.getMessage());
                    queue.result(text.alertId(), false, null, now());
                    continue;
                } catch (RuntimeException e) {
                    refused++;
                    log.warn("A Safety Circle text failed ({})", e.getClass().getSimpleName());
                    queue.result(text.alertId(), false, null, now());
                    continue;
                }
                queue.result(text.alertId(), true, reference, now());
                accepted++;
            }
            if (accepted + refused > 0) log.info("Safety Circle texts: {} accepted, {} not accepted", accepted, refused);
            return accepted;
        } finally {
            lock.unlock();
        }
    }

    private void safeRun() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("The alert queue run stopped ({}); the next run tries again", e.getClass().getSimpleName());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    @PreDestroy
    void stop() {
        kicker.shutdownNow();
    }
}
