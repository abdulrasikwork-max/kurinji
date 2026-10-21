package com.safesphere.backend.evaluation.service;

import com.safesphere.backend.community.service.CommunityService.VisibleSignal;
import com.safesphere.backend.community.service.SignalVisibility;
import com.safesphere.backend.community.service.SignalVisibility.VisibleSet;
import com.safesphere.backend.config.EvaluationProperties;
import com.safesphere.backend.config.IntelligenceProperties;
import com.safesphere.backend.config.IntelligenceProperties.Rules2Mode;
import com.safesphere.backend.evaluation.entity.EvaluationCounter.Key;
import com.safesphere.backend.evaluation.entity.Metric;
import com.safesphere.backend.evaluation.repository.EvaluationCounterRepository;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPatterns;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.CellPicture;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Evidence;
import com.safesphere.backend.intelligence.service.AreaIntelligencePolicy.Model;
import com.safesphere.backend.intelligence.service.AreaIntelligenceService;
import com.safesphere.backend.intelligence.service.PatternService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 9B weekly snapshot (docs/PHASE9.md §27), from the clean-up job: the first run of each ISO week counts what
 * anyone could see at that moment, system-wide — visible signals by group and confirmation, ~1 km areas by the active
 * rules' outlook and information level, and (while rules-2 is in SHADOW) how many areas rules-2 would show more or less
 * cautiously. It is the durable, weekly form of 9D's hourly shadow log line, i.e. the evidence a promotion of rules-2
 * would need. It reads exactly the visible set (SignalVisibility, the one definition) and stores counts only: no cell,
 * no signal, no person. All rows of one week are written in one transaction, so a failed snapshot leaves nothing and is
 * simply tried again on the next run. Also applies the counters' retention.
 */
@Component
public class WeeklySnapshot {

    private static final Logger log = LoggerFactory.getLogger(WeeklySnapshot.class);

    /** What one run did (counts only). */
    public record Result(boolean taken, int areas, int signals, int deleted) {
        static final Result NOTHING = new Result(false, 0, 0, 0);
    }

    private final SignalVisibility visibility;
    private final PatternService patterns;
    private final AreaIntelligenceService intelligence;
    private final IntelligenceProperties intelligenceProperties;
    private final EvaluationCounterRepository counters;
    private final OutcomeRecorder recorder;
    private final EvaluationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WeeklySnapshot(SignalVisibility visibility, PatternService patterns, AreaIntelligenceService intelligence,
                          IntelligenceProperties intelligenceProperties, EvaluationCounterRepository counters,
                          OutcomeRecorder recorder, EvaluationProperties properties, Clock clock,
                          PlatformTransactionManager transactions) {
        this.visibility = visibility;
        this.patterns = patterns;
        this.intelligence = intelligence;
        this.intelligenceProperties = intelligenceProperties;
        this.counters = counters;
        this.recorder = recorder;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Called by the clean-up job on every run; does real work once a week. Never throws. */
    public Result run(LocalDateTime now) {
        try {
            Result result = transaction.execute(s -> runInTransaction(now));
            if (result != null && result.taken()) {
                // Counts only: never a cell, a category with a place, or an account.
                log.info("Evaluation weekly snapshot: {} visible signal(s) in {} area(s)", result.signals(), result.areas());
            }
            return result == null ? Result.NOTHING : result;
        } catch (RuntimeException e) {
            log.warn("Evaluation weekly snapshot skipped: {}", e.getClass().getSimpleName());
            return Result.NOTHING;
        }
    }

    private Result runInTransaction(LocalDateTime now) {
        LocalDate week = OutcomeRecorder.weekOf(clock);
        if (!properties.enabled()) {
            // Switched off: nothing is counted and what was stored goes (as for pattern memory).
            return new Result(false, 0, 0, counters.deleteEverything());
        }
        int deleted = counters.deleteOlderThan(week.minusWeeks(properties.retentionWeeks() - 1L));
        if (counters.existsByKeyWeekStartAndKeyMetric(week, Metric.SNAPSHOT_TAKEN)) {
            return new Result(false, 0, 0, deleted);
        }
        Map<Key, Integer> rows = new LinkedHashMap<>();
        put(rows, week, Metric.SNAPSHOT_TAKEN, OutcomeRecorder.NONE, OutcomeRecorder.NONE);

        VisibleSet set = visibility.setEverywhere(now);
        for (VisibleSignal v : set.signals()) {
            String evidenceClass = switch (v.assessment().confidence()) {
                case LOW -> "UNCONFIRMED";
                case MODERATE -> "CONFIRMED";
                case HIGH -> "STRONG";
            };
            put(rows, week, Metric.VISIBLE_SIGNALS, v.signal().getCategory().group().name() + "." + evidenceClass,
                    OutcomeRecorder.NONE);
        }
        List<String> cells = List.copyOf(new TreeSet<>(set.signals().stream()
                .map(v -> v.signal().getAreaCell()).toList()));
        if (!cells.isEmpty()) {
            Map<String, CellPatterns> recurring = patterns.recurringIn(cells);
            List<Evidence> evidence = AreaIntelligenceService.evidence(set.signals());
            Model active = intelligence.activeModel();
            List<CellPicture> shown = AreaIntelligencePolicy.cells(active, cells, evidence, recurring, set.people());
            for (CellPicture picture : shown) {
                put(rows, week, Metric.AREAS_BY_OUTLOOK, picture.outlook().name(), active.version());
                put(rows, week, Metric.AREAS_BY_INFORMATION, picture.information().name(), active.version());
            }
            if (intelligenceProperties.rules2Mode() == Rules2Mode.SHADOW) {
                // The same comparison as AreaModelShadow's hourly log line: concern level, cell by cell.
                List<CellPicture> candidate = AreaIntelligencePolicy.cells(Model.RULES_2, cells, evidence, recurring,
                        set.people());
                for (int i = 0; i < cells.size(); i++) {
                    int difference = candidate.get(i).concern().compareTo(shown.get(i).concern());
                    String agreement = difference < 0 ? "LESS_CAUTIOUS" : difference > 0 ? "MORE_CAUTIOUS" : "SAME";
                    put(rows, week, Metric.SHADOW_PICTURE, agreement, Model.RULES_2.version());
                }
            }
        }
        rows.forEach(recorder::add);
        return new Result(true, cells.size(), set.signals().size(), deleted);
    }

    private static void put(Map<Key, Integer> rows, LocalDate week, Metric metric, String dimension, String version) {
        if (!OutcomeRecorder.storable(dimension, version)) throw new IllegalArgumentException("not a counter dimension");
        rows.merge(new Key(week, metric, dimension, version), 1, Integer::sum);
    }
}
