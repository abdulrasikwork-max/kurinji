package com.safesphere.backend.evaluation.dto;

import java.time.LocalDate;
import java.util.List;

/** Phase 9B: the ADMIN evaluation report (GET /api/admin/evaluation). Counts, rates and gate status; no place or person. */
public final class EvaluationDtos {

    private EvaluationDtos() {
    }

    public record Report(String notice, LocalDate fromWeek, LocalDate toWeek, boolean currentWeekIncomplete,
                         boolean countersEnabled, List<Row> counters, List<Rate> rates, Gate g1) {
    }

    /** One weekly counter. */
    public record Row(LocalDate weekStart, String metric, String dimension, String modelVersion, int value) {
    }

    public enum RateStatus {
        /** Enough data: value and Wilson 95 % interval given. */
        OK,
        /** Fewer cases than the minimum: no number on purpose. */
        NOT_ENOUGH_DATA,
        /** The numerator exceeds the denominator in this window (events straddle its edges): no number. */
        INCONSISTENT
    }

    /**
     * One derived rate over the window (or the latest snapshot). [lowerBound] marks a rate that can only under-count
     * (e.g. false confirmations: only what moderators caught).
     */
    public record Rate(String name, String dimension, long numerator, long denominator, int minimum, RateStatus status,
                       Double value, Double low, Double high, boolean lowerBound) {
    }

    /**
     * Gate G1 (docs/PHASE9_DESIGN.md §4.4): decided FLAGS + COORDINATION cases in each of the last three 4-week blocks
     * (oldest first; needs >= 20 each), moderators who decided a case in the last 30 days (needs >= 3). The written
     * privacy review it also needs cannot be checked by software.
     */
    public record Gate(String status, List<Long> decidedCasesPerFourWeeks, long activeModerators, String note) {
    }
}
