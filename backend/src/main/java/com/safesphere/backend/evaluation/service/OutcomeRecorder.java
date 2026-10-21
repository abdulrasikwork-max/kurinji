package com.safesphere.backend.evaluation.service;

import com.safesphere.backend.community.entity.SignalCategory;
import com.safesphere.backend.config.EvaluationProperties;
import com.safesphere.backend.evaluation.entity.EvaluationCounter.Key;
import com.safesphere.backend.evaluation.entity.Metric;
import com.safesphere.backend.evaluation.repository.EvaluationCounterRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 9B outcome counters (docs/PHASE9.md §27): community and moderation tell it what happened, it adds one to a
 * weekly, system-wide counter. The rules:
 *   - AFTER COMMIT: counts made inside a transaction are written only if that transaction commits (a rolled-back report
 *     counts nothing), in a transaction of their own;
 *   - BEST EFFORT: a counter that cannot be written is skipped; the report, decision or clean-up never fails because of
 *     it, and the log line holds only the exception's class name;
 *   - VOCABULARY ONLY: dimensions are built from enums and checked against the same pattern as the V15 CHECK, so no
 *     cell, id or free text can be counted; there is no method that takes a place, a person or a time.
 * It never reads a counter (only EvaluationService does, for the ADMIN report), so nothing that decides what people see
 * can depend on one.
 */
@Component
public class OutcomeRecorder {

    /** The "no dimension / no model version" value. */
    public static final String NONE = "-";
    /** Same patterns as V15's CHECKs (upper case and underscores; a short lower-case name with a version number). */
    static final Pattern DIMENSION = Pattern.compile("^(-|[A-Z_]+([.][A-Z_]+)?)$");
    static final Pattern VERSION = Pattern.compile("^(-|[a-z]+-[0-9]+([.][0-9]+)?)$");

    private static final Logger log = LoggerFactory.getLogger(OutcomeRecorder.class);

    /** How a signal row ended (moderator removal is counted separately, as SIGNAL_REMOVED). */
    public enum SignalEnd { EXPIRED, RETIRED, WITHDRAWN }

    private final EvaluationCounterRepository counters;
    private final EvaluationProperties properties;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;
    /** coord-1 SHADOW: signals of groups already counted this week (in memory only; a restart may count a group again). */
    private LocalDate shadowWeek;
    private final Set<Long> shadowSeen = new HashSet<>();

    public OutcomeRecorder(EvaluationCounterRepository counters, EvaluationProperties properties, Clock clock,
                           PlatformTransactionManager transactions) {
        this.counters = counters;
        this.properties = properties;
        this.clock = clock;
        if (transactions == null) {
            this.ownTransaction = null;
        } else {
            this.ownTransaction = new TransactionTemplate(transactions);
            this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    // ---- What happened (typed: only vocabulary goes in) ------------------------------------------------------------

    public void signalStarted(SignalCategory category) {
        count(Metric.SIGNAL_STARTED, category.group().name(), NONE);
    }

    public void signalJoined(SignalCategory category) {
        count(Metric.SIGNAL_JOINED_NEIGHBOUR, category.group().name(), NONE);
    }

    public void signalConfirmed(SignalCategory category) {
        count(Metric.SIGNAL_CONFIRMED, category.group().name(), NONE);
    }

    /** [confirmed] = independent people had confirmed it while it was visible (Phase 8's reached_confirmed). */
    public void signalEnded(SignalCategory category, SignalEnd end, boolean confirmed) {
        count(Metric.SIGNAL_ENDED, category.group().name() + "." + end.name() + "_" + confirmedWord(confirmed), NONE);
    }

    /** A moderator's REMOVE; [reason] is the decision's reason code. */
    public void signalRemoved(SignalCategory category, boolean confirmed, Enum<?> reason) {
        count(Metric.SIGNAL_REMOVED, category.group().name() + "." + confirmedWord(confirmed) + "_" + reason.name(), NONE);
    }

    public void caseOpened(CaseTrigger trigger) {
        count(Metric.CASE_OPENED, trigger.name(), trigger.version());
    }

    /** [outcome] is the case outcome (REMOVED or DISMISSED). */
    public void caseClosed(CaseTrigger trigger, Enum<?> outcome) {
        count(Metric.CASE_CLOSED, trigger.name() + "." + outcome.name(), trigger.version());
    }

    public void caseUnreviewed(CaseTrigger trigger) {
        count(Metric.CASE_UNREVIEWED, trigger.name(), trigger.version());
    }

    /**
     * coord-1 in SHADOW found a group (it runs hourly, so the same group is found again and again): counted once per
     * week, the first time none of its signals belonged to a group already counted this week. That is what ACTIVE would
     * open with an unlimited budget (a group with an open case on any of its signals is not asked about again).
     */
    public synchronized void coordinationShadow(CaseTrigger rule, Collection<Long> signalIds) {
        if (!properties.enabled()) return;
        try {
            LocalDate week = weekOf(clock);
            if (!week.equals(shadowWeek)) {
                shadowWeek = week;
                shadowSeen.clear();
            }
            boolean seen = signalIds.stream().anyMatch(shadowSeen::contains);
            shadowSeen.addAll(signalIds);
            if (!seen) count(Metric.COORDINATION_SHADOW, rule.name(), rule.version());
        } catch (RuntimeException e) {
            skipped(e);
        }
    }

    // ---- Mechanics ----------------------------------------------------------------------------------------------------

    /** Monday (UTC) of the current ISO week: the only time a counter knows. */
    public static LocalDate weekOf(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** A dimension and version the V15 CHECKs accept (so a counter can never hold a cell, an id or text). */
    public static boolean storable(String dimension, String version) {
        return dimension != null && version != null && DIMENSION.matcher(dimension).matches()
                && VERSION.matcher(version).matches();
    }

    private void count(Metric metric, String dimension, String version) {
        if (!properties.enabled()) return;
        try {
            if (!storable(dimension, version)) throw new IllegalArgumentException("not a counter dimension");
            Key key = new Key(weekOf(clock), metric, dimension, version);
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                write(Map.of(key, 1));
                return;
            }
            pending().merge(key, 1, Integer::sum);
        } catch (RuntimeException e) {
            skipped(e);
        }
    }

    /** This transaction's counts, written once after it commits (and dropped if it rolls back). */
    @SuppressWarnings("unchecked")
    private Map<Key, Integer> pending() {
        Map<Key, Integer> batch = (Map<Key, Integer>) TransactionSynchronizationManager.getResource(this);
        if (batch != null) return batch;
        Map<Key, Integer> created = new LinkedHashMap<>();
        TransactionSynchronizationManager.bindResource(this, created);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                write(created);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(OutcomeRecorder.this);
            }
        });
        return created;
    }

    private void write(Map<Key, Integer> batch) {
        for (Map.Entry<Key, Integer> entry : batch.entrySet()) {
            try {
                ownTransaction.executeWithoutResult(s -> add(entry.getKey(), entry.getValue()));
            } catch (DataIntegrityViolationException raced) {
                // Another transaction inserted the same key a moment ago: add to its row instead.
                try {
                    ownTransaction.executeWithoutResult(s -> add(entry.getKey(), entry.getValue()));
                } catch (RuntimeException e) {
                    skipped(e);
                }
            } catch (RuntimeException e) {
                skipped(e);
            }
        }
    }

    /** Adds [delta] to the counter in the CURRENT transaction (also used by the weekly snapshot). */
    void add(Key key, int delta) {
        if (counters.increment(key.getWeekStart(), key.getMetric(), key.getDimension(), key.getModelVersion(), delta) == 0) {
            counters.insert(key.getWeekStart(), key.getMetric().name(), key.getDimension(), key.getModelVersion(), delta);
        }
    }

    private static String confirmedWord(boolean confirmed) {
        return confirmed ? "CONFIRMED" : "UNCONFIRMED";
    }

    private static void skipped(RuntimeException e) {
        // Never the dimension or anything about the event: only that a count was lost.
        log.warn("Evaluation counter skipped: {}", e.getClass().getSimpleName());
    }
}
