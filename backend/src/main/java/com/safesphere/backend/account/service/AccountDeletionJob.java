package com.safesphere.backend.account.service;

import com.safesphere.backend.auth.service.DeviceSessionService;
import com.safesphere.backend.config.AccountProperties;
import com.safesphere.backend.evidence.service.EvidenceStorageReconciler;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 10A.3: every 15 minutes, deletes the accounts whose grace period is over (AccountEraser, at most
 * app.account.deletion-batch per run), then sweeps the evidence folder for ciphertext no record refers to any more
 * (the same reconciliation that runs at start-up). It deletes data only and never sends anything.
 * Turned off with app.account.deletion-job-enabled=false (the tests call [run] directly).
 */
@Component
public class AccountDeletionJob {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionJob.class);

    private final UserRepository users;
    private final AccountEraser eraser;
    private final AccountService accounts;
    private final EvidenceStorageReconciler reconciler;
    private final AccountProperties properties;
    private final Clock clock;
    private final boolean enabled;
    private final DeviceSessionService sessions;

    public AccountDeletionJob(UserRepository users, AccountEraser eraser, AccountService accounts,
                              EvidenceStorageReconciler reconciler, AccountProperties properties, Clock clock,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${app.account.deletion-job-enabled:true}") boolean enabled,
                              DeviceSessionService sessions) {
        this.sessions = sessions;
        this.users = users;
        this.eraser = eraser;
        this.accounts = accounts;
        this.reconciler = reconciler;
        this.properties = properties;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.account.deletion-interval:PT15M}", initialDelayString = "PT2M")
    public void scheduled() {
        if (enabled) run();
    }

    /** One run; returns how many accounts ended in each outcome. Never throws (one failure never blocks the others). */
    public Map<AccountEraser.Outcome, Integer> run() {
        Map<AccountEraser.Outcome, Integer> outcomes = new EnumMap<>(AccountEraser.Outcome.class);
        accounts.evictExpired();
        try {
            sessions.purge(); // Phase 11 C1: device sessions that ended long ago
        } catch (RuntimeException e) {
            log.warn("Device session clean-up failed ({}); trying again next run", e.getClass().getSimpleName());
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Long> due = users.findIdsDueForDeletion(now, PageRequest.of(0, properties.deletionBatch()));
        for (Long id : due) {
            AccountEraser.Outcome outcome;
            try {
                outcome = eraser.eraseIfDue(id);
            } catch (RuntimeException e) {
                log.warn("Account deletion failed for one account ({}); trying again next run", e.getClass().getSimpleName());
                outcome = AccountEraser.Outcome.FILES_FAILED;
            }
            outcomes.merge(outcome, 1, Integer::sum);
        }
        int erased = outcomes.getOrDefault(AccountEraser.Outcome.DELETED, 0)
                + outcomes.getOrDefault(AccountEraser.Outcome.ANONYMISED, 0);
        if (erased > 0) reconciler.reconcile();
        if (!due.isEmpty()) {
            log.info("Account deletion: {} deleted, {} anonymised, {} postponed, {} to retry",
                    outcomes.getOrDefault(AccountEraser.Outcome.DELETED, 0),
                    outcomes.getOrDefault(AccountEraser.Outcome.ANONYMISED, 0),
                    outcomes.getOrDefault(AccountEraser.Outcome.POSTPONED, 0),
                    outcomes.getOrDefault(AccountEraser.Outcome.FILES_FAILED, 0));
        }
        return outcomes;
    }
}
