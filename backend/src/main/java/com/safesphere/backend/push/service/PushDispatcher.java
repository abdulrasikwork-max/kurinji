package com.safesphere.backend.push.service;

import com.safesphere.backend.config.PushProperties;
import com.safesphere.backend.push.service.PushQueue.Claimed;
import com.safesphere.backend.push.service.PushSender.Outcome;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
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
 * N7 (D35 = N-127): works the push queue every couple of seconds ([PushProperties#dispatchInterval]) and at once after a
 * push is queued ([kick]). One run: for each due row, claim (one transaction) → the provider (NO transaction) → the result
 * (one transaction). One run at a time. Turned off with app.push.job-enabled=false (the tests call [runOnce]).
 *
 * The second scheduled task that sends something (after AlertDispatcher). Logs counts per outcome and FCM status codes
 * only: never a token, a person, a subject or anything they wrote.
 */
@Service
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final PushQueue queue;
    private final PushSender sender;
    private final PushProperties properties;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean kickPending = new AtomicBoolean();
    private final ExecutorService kicker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "push-kick");
        thread.setDaemon(true);
        return thread;
    });

    public PushDispatcher(PushQueue queue, PushSender sender, PushProperties properties, Clock clock) {
        this.queue = queue;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.push.dispatch-interval:PT2S}", initialDelayString = "PT20S")
    public void scheduled() {
        if (properties.jobEnabled()) safeRun();
    }

    /** Something was queued: work the queue now instead of at the next tick (asynchronous, never blocks). */
    public void kick() {
        if (!properties.jobEnabled()) return;
        if (kickPending.compareAndSet(false, true)) {
            kicker.execute(() -> {
                kickPending.set(false);
                safeRun();
            });
        }
    }

    /** One run. Returns how many pushes the provider accepted. */
    public int runOnce() {
        if (!lock.tryLock()) return 0;
        try {
            Map<PushSender.Result, Integer> counts = new EnumMap<>(PushSender.Result.class);
            String lastCode = null;
            for (Long id : queue.dueIds(now())) {
                Optional<Claimed> claimed = queue.claim(id, now());
                if (claimed.isEmpty()) continue;
                Outcome outcome;
                try {
                    outcome = sender.send(claimed.get().push());
                } catch (RuntimeException e) {
                    outcome = Outcome.of(PushSender.Result.RETRY, e.getClass().getSimpleName());
                }
                queue.result(claimed.get(), outcome, now());
                counts.merge(outcome.result(), 1, Integer::sum);
                if (outcome.result() != PushSender.Result.ACCEPTED) lastCode = outcome.code();
            }
            if (!counts.isEmpty()) {
                log.info("Pushes: {} accepted, {} retry, {} unregistered, {} failed{}",
                        counts.getOrDefault(PushSender.Result.ACCEPTED, 0), counts.getOrDefault(PushSender.Result.RETRY, 0),
                        counts.getOrDefault(PushSender.Result.UNREGISTERED, 0), counts.getOrDefault(PushSender.Result.FAILED, 0),
                        lastCode == null ? "" : " (last status " + lastCode + ")");
            }
            if (counts.getOrDefault(PushSender.Result.AUTH, 0) > 0) {
                log.warn("FCM refused IRL's credentials ({}): check FCM_CREDENTIALS_FILE; pushes wait and retry", lastCode);
            }
            return counts.getOrDefault(PushSender.Result.ACCEPTED, 0);
        } finally {
            lock.unlock();
        }
    }

    private void safeRun() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("The push queue run stopped ({}); the next run tries again", e.getClass().getSimpleName());
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
