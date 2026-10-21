package com.safesphere.backend.evaluation.service;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.config.EvaluationProperties;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Report;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Row;
import com.safesphere.backend.evaluation.entity.EvaluationCounter;
import com.safesphere.backend.evaluation.repository.EvaluationCounterRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 9B: the only reader of the counters. Builds the ADMIN report; the caller (ModerationService) has already checked
 * that the caller is an ADMIN. There is no export, no per-area view and no client screen.
 */
@Service
public class EvaluationService {

    public static final int MAX_WEEKS = 104;
    static final String NOTICE = "System-wide weekly counts, recorded by the server itself after each event commits. "
            + "Best effort: a counter that fails is skipped, so every value is a lower bound. No place, person, signal or "
            + "time finer than a week is stored. Publication rule: system-wide only, values below 10 shown as "
            + "\"fewer than 10\", and say \"weekly, system-wide, lower bound\".";

    private final EvaluationCounterRepository counters;
    private final EvaluationProperties properties;
    private final Clock clock;

    public EvaluationService(EvaluationCounterRepository counters, EvaluationProperties properties, Clock clock) {
        this.counters = counters;
        this.properties = properties;
        this.clock = clock;
    }

    /** The last [weeks] weeks (the current, incomplete one included), with rates and gate G1. */
    @Transactional(readOnly = true)
    public Report report(int weeks, long activeModerators) {
        if (weeks < 1 || weeks > MAX_WEEKS) throw new InvalidRequestException("weeks must be between 1 and " + MAX_WEEKS);
        LocalDate current = OutcomeRecorder.weekOf(clock);
        LocalDate from = current.minusWeeks(weeks - 1L);
        LocalDate gateFrom = current.minusWeeks(12);
        List<Row> all = counters.findByKeyWeekStartGreaterThanEqual(from.isBefore(gateFrom) ? from : gateFrom).stream()
                .map(EvaluationService::row)
                .sorted(Comparator.comparing(Row::weekStart).reversed().thenComparing(Row::metric)
                        .thenComparing(Row::dimension).thenComparing(Row::modelVersion))
                .toList();
        List<Row> window = all.stream().filter(r -> !r.weekStart().isBefore(from)).toList();
        return new Report(NOTICE, from, current, true, properties.enabled(), window, EvaluationReport.rates(window),
                EvaluationReport.g1(all, current, activeModerators));
    }

    private static Row row(EvaluationCounter c) {
        return new Row(c.getKey().getWeekStart(), c.getKey().getMetric().name(), c.getKey().getDimension(),
                c.getKey().getModelVersion(), c.getOccurrences());
    }
}
