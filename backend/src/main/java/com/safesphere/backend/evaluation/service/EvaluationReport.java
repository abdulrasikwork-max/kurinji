package com.safesphere.backend.evaluation.service;

import com.safesphere.backend.community.entity.SignalCategory.SignalGroup;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Gate;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Rate;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.RateStatus;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Row;
import com.safesphere.backend.evaluation.entity.Metric;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Phase 9B: turns weekly counters into the rates of docs/PHASE9_DESIGN.md §11. Pure (no database, no clock), so every
 * number in the ADMIN report is unit-tested. Every rate has a Wilson 95 % interval, and below its minimum count it says
 * NOT_ENOUGH_DATA instead of a number. Rates are over the requested window, except the snapshot shares, which use the
 * latest weekly snapshot only (summing snapshots would count the same long-lived signal several times).
 */
public final class EvaluationReport {

    static final double Z = 1.96;
    /** §11 minimum counts. */
    static final int MIN_SIGNALS = 20;
    static final int MIN_DECIDED_CASES = 10;
    /** Gate G1 (§4.4). */
    static final int G1_DECIDED_PER_BLOCK = 20;
    static final int G1_MODERATORS = 3;
    static final Set<CaseTrigger> G1_TRIGGERS = Set.of(CaseTrigger.FLAGS, CaseTrigger.LINKED_GROUP,
            CaseTrigger.SUPPORT_CORE, CaseTrigger.EXCLUSIVE_PAIR, CaseTrigger.COORDINATION);

    private EvaluationReport() {
    }

    /** The derived rates for [rows] (the window). */
    public static List<Rate> rates(Collection<Row> rows) {
        List<Rate> rates = new ArrayList<>();
        for (SignalGroup group : SignalGroup.values()) {
            String g = group.name();
            long started = sum(rows, Metric.SIGNAL_STARTED, d -> d.equals(g));
            long confirmedEnded = sum(rows, Metric.SIGNAL_ENDED, d -> d.startsWith(g + ".") && d.endsWith("_CONFIRMED"))
                    + sum(rows, Metric.SIGNAL_REMOVED, d -> d.startsWith(g + ".CONFIRMED_"));
            rates.add(rate("CONFIRMATION_YIELD", g, sum(rows, Metric.SIGNAL_CONFIRMED, d -> d.equals(g)), started,
                    MIN_SIGNALS, false));
            rates.add(rate("FALSE_CONFIRMATION_RATE", g, sum(rows, Metric.SIGNAL_REMOVED,
                    d -> d.equals(g + ".CONFIRMED_FALSE_INFORMATION") || d.equals(g + ".CONFIRMED_SPAM_OR_COORDINATED")),
                    confirmedEnded, MIN_SIGNALS, true));
            rates.add(rate("RETIRED_AFTER_CONFIRMATION", g, sum(rows, Metric.SIGNAL_ENDED,
                    d -> d.equals(g + ".RETIRED_CONFIRMED")), confirmedEnded, MIN_SIGNALS, false));
            if (group != SignalGroup.HAVEN) {
                rates.add(rate("JOIN_YIELD", g, sum(rows, Metric.SIGNAL_JOINED_NEIGHBOUR, d -> d.equals(g)), started,
                        MIN_SIGNALS, false));
            }
        }
        for (CaseTrigger trigger : CaseTrigger.values()) {
            String t = trigger.name();
            long removed = sum(rows, Metric.CASE_CLOSED, d -> d.equals(t + ".REMOVED"));
            long dismissed = sum(rows, Metric.CASE_CLOSED, d -> d.equals(t + ".DISMISSED"));
            rates.add(rate("TRIGGER_PRECISION", t, removed, removed + dismissed, MIN_DECIDED_CASES, false));
            rates.add(rate("UNREVIEWED_SHARE", t, sum(rows, Metric.CASE_UNREVIEWED, d -> d.equals(t)),
                    sum(rows, Metric.CASE_OPENED, d -> d.equals(t)), 1, false));
        }
        LocalDate latest = rows.stream().filter(r -> r.metric().equals(Metric.SNAPSHOT_TAKEN.name()))
                .map(Row::weekStart).max(LocalDate::compareTo).orElse(null);
        List<Row> snapshot = latest == null ? List.of()
                : rows.stream().filter(r -> r.weekStart().equals(latest)).toList();
        rates.add(rate("CONFIRMED_SHARE_OF_VISIBLE", "-",
                sum(snapshot, Metric.VISIBLE_SIGNALS, d -> d.endsWith(".CONFIRMED") || d.endsWith(".STRONG")),
                sum(snapshot, Metric.VISIBLE_SIGNALS, d -> true), 1, false));
        long compared = sum(snapshot, Metric.SHADOW_PICTURE, d -> true);
        rates.add(rate("SHADOW_LESS_CAUTIOUS", "-", sum(snapshot, Metric.SHADOW_PICTURE, d -> d.equals("LESS_CAUTIOUS")),
                compared, 1, false));
        rates.add(rate("SHADOW_MORE_CAUTIOUS", "-", sum(snapshot, Metric.SHADOW_PICTURE, d -> d.equals("MORE_CAUTIOUS")),
                compared, 1, false));
        return rates;
    }

    /**
     * Gate G1 from [rows] covering at least the 12 complete weeks before [currentWeek] (the current, incomplete week
     * does not count): three 4-week blocks, oldest first.
     */
    public static Gate g1(Collection<Row> rows, LocalDate currentWeek, long activeModerators) {
        List<Long> blocks = new ArrayList<>();
        boolean counts = activeModerators >= G1_MODERATORS;
        for (int block = 2; block >= 0; block--) {
            LocalDate from = currentWeek.minusWeeks(4L * block + 4);
            LocalDate to = currentWeek.minusWeeks(4L * block); // exclusive
            long decided = rows.stream().filter(r -> r.metric().equals(Metric.CASE_CLOSED.name())
                            && !r.weekStart().isBefore(from) && r.weekStart().isBefore(to)
                            && G1_TRIGGERS.stream().anyMatch(t -> r.dimension().startsWith(t.name() + ".")))
                    .mapToLong(Row::value).sum();
            blocks.add(decided);
            if (decided < G1_DECIDED_PER_BLOCK) counts = false;
        }
        return new Gate(counts ? "COUNTS_MET_PRIVACY_REVIEW_REQUIRED" : "NOT_MET", List.copyOf(blocks), activeModerators,
                "G1 needs >= " + G1_DECIDED_PER_BLOCK + " decided FLAGS + COORDINATION cases in each 4-week block, >= "
                        + G1_MODERATORS + " active moderators and a written privacy review (not checked here). "
                        + "G2 and G3 need stored training examples, which do not exist: no learned model is built.");
    }

    /** A rate with its Wilson 95 % interval, or no number below [minimum] or when the window makes it inconsistent. */
    public static Rate rate(String name, String dimension, long numerator, long denominator, int minimum,
                            boolean lowerBound) {
        if (denominator < Math.max(1, minimum)) {
            return new Rate(name, dimension, numerator, denominator, minimum, RateStatus.NOT_ENOUGH_DATA, null, null, null,
                    lowerBound);
        }
        if (numerator > denominator) {
            return new Rate(name, dimension, numerator, denominator, minimum, RateStatus.INCONSISTENT, null, null, null,
                    lowerBound);
        }
        double[] interval = wilson(numerator, denominator);
        return new Rate(name, dimension, numerator, denominator, minimum, RateStatus.OK,
                round((double) numerator / denominator), round(interval[0]), round(interval[1]), lowerBound);
    }

    /** Wilson score interval (95 %) for k successes in n trials; n > 0. */
    public static double[] wilson(long k, long n) {
        double p = (double) k / n;
        double z2 = Z * Z;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denominator;
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    private static long sum(Collection<Row> rows, Metric metric, Predicate<String> dimension) {
        return rows.stream().filter(r -> r.metric().equals(metric.name()) && dimension.test(r.dimension()))
                .mapToLong(Row::value).sum();
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
