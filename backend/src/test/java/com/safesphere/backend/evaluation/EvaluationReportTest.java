package com.safesphere.backend.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.safesphere.backend.evaluation.dto.EvaluationDtos.Gate;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Rate;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.RateStatus;
import com.safesphere.backend.evaluation.dto.EvaluationDtos.Row;
import com.safesphere.backend.evaluation.service.EvaluationReport;
import com.safesphere.backend.evaluation.service.OutcomeRecorder;
import com.safesphere.backend.support.MutableClock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 9B: the numbers in the ADMIN report (pure, docs/PHASE9_DESIGN.md §11, §4.4). */
class EvaluationReportTest {

    private static final LocalDate WEEK = LocalDate.of(2026, 10, 19); // a Monday

    @Test
    void wilsonInterval_matchesTheTextbookValues() {
        double[] half = EvaluationReport.wilson(5, 10);
        assertEquals(0.2366, half[0], 0.0005);
        assertEquals(0.7634, half[1], 0.0005);
        double[] none = EvaluationReport.wilson(0, 20);
        assertEquals(0.0, none[0], 0.0001);
        assertEquals(0.1611, none[1], 0.0005, "zero successes still has an honest upper bound");
        double[] all = EvaluationReport.wilson(20, 20);
        assertEquals(0.8389, all[0], 0.0005);
        assertEquals(1.0, all[1], 0.0001);
    }

    @Test
    void belowTheMinimum_thereIsNoNumber() {
        Rate few = EvaluationReport.rate("TRIGGER_PRECISION", "FLAGS", 9, 9, 10, false);
        assertEquals(RateStatus.NOT_ENOUGH_DATA, few.status());
        assertNull(few.value());
        assertNull(few.low());
        assertNull(few.high());
        Rate enough = EvaluationReport.rate("TRIGGER_PRECISION", "FLAGS", 9, 10, 10, false);
        assertEquals(RateStatus.OK, enough.status());
        assertEquals(0.9, enough.value());
        assertTrue(enough.low() < 0.9 && enough.high() > 0.9);
        assertEquals(RateStatus.NOT_ENOUGH_DATA, EvaluationReport.rate("X", "-", 0, 0, 1, false).status(),
                "nothing happened: no rate, never 0 %");
    }

    @Test
    void aWindowThatCutsEventsApart_isReportedAsInconsistent_notAsAPercentageAboveOne() {
        Rate odd = EvaluationReport.rate("CONFIRMATION_YIELD", "INCIDENT", 25, 21, 20, false);
        assertEquals(RateStatus.INCONSISTENT, odd.status());
        assertNull(odd.value());
    }

    @Test
    void rates_comeFromTheRightCounters() {
        List<Row> rows = new ArrayList<>();
        rows.add(row(WEEK, "SIGNAL_STARTED", "CONDITION", 40));
        rows.add(row(WEEK, "SIGNAL_CONFIRMED", "CONDITION", 10));
        rows.add(row(WEEK, "SIGNAL_JOINED_NEIGHBOUR", "CONDITION", 8));
        rows.add(row(WEEK, "SIGNAL_ENDED", "CONDITION.EXPIRED_CONFIRMED", 15));
        rows.add(row(WEEK, "SIGNAL_ENDED", "CONDITION.RETIRED_CONFIRMED", 3));
        rows.add(row(WEEK, "SIGNAL_ENDED", "CONDITION.EXPIRED_UNCONFIRMED", 50)); // must NOT count as confirmed
        rows.add(row(WEEK, "SIGNAL_REMOVED", "CONDITION.CONFIRMED_FALSE_INFORMATION", 1));
        rows.add(row(WEEK, "SIGNAL_REMOVED", "CONDITION.CONFIRMED_DUPLICATE", 1));
        rows.add(row(WEEK, "SIGNAL_REMOVED", "CONDITION.UNCONFIRMED_SPAM_OR_COORDINATED", 7)); // not a confirmed one
        rows.add(rowV(WEEK, "CASE_CLOSED", "SUPPORT_CORE.REMOVED", "coord-1", 4));
        rows.add(rowV(WEEK, "CASE_CLOSED", "SUPPORT_CORE.DISMISSED", "coord-1", 6));
        rows.add(rowV(WEEK, "CASE_OPENED", "SUPPORT_CORE", "coord-1", 12));
        rows.add(rowV(WEEK, "CASE_UNREVIEWED", "SUPPORT_CORE", "coord-1", 2));

        List<Rate> rates = EvaluationReport.rates(rows);
        Rate yield = find(rates, "CONFIRMATION_YIELD", "CONDITION");
        assertEquals(10, yield.numerator());
        assertEquals(40, yield.denominator());
        assertEquals(0.25, yield.value());
        Rate fcr = find(rates, "FALSE_CONFIRMATION_RATE", "CONDITION");
        assertEquals(1, fcr.numerator(), "only confirmed signals removed as false or coordinated");
        assertEquals(20, fcr.denominator(), "15 expired + 3 retired + 2 removed, all confirmed");
        assertTrue(fcr.lowerBound(), "only what moderators caught");
        assertEquals(3, find(rates, "RETIRED_AFTER_CONFIRMATION", "CONDITION").numerator());
        assertEquals(0.2, find(rates, "JOIN_YIELD", "CONDITION").value());
        Rate precision = find(rates, "TRIGGER_PRECISION", "SUPPORT_CORE");
        assertEquals(0.4, precision.value());
        assertEquals(10, precision.denominator());
        assertEquals(2, find(rates, "UNREVIEWED_SHARE", "SUPPORT_CORE").numerator());
        assertFalse(rates.stream().anyMatch(r -> r.name().equals("JOIN_YIELD") && r.dimension().equals("HAVEN")),
                "safe places never join, so there is no join yield for them");
    }

    @Test
    void snapshotShares_useOnlyTheLatestSnapshot() {
        LocalDate older = WEEK.minusWeeks(1);
        List<Row> rows = List.of(
                row(older, "SNAPSHOT_TAKEN", "-", 1), row(older, "VISIBLE_SIGNALS", "INCIDENT.UNCONFIRMED", 9),
                row(WEEK, "SNAPSHOT_TAKEN", "-", 1), row(WEEK, "VISIBLE_SIGNALS", "INCIDENT.UNCONFIRMED", 3),
                row(WEEK, "VISIBLE_SIGNALS", "CONDITION.CONFIRMED", 1),
                row(WEEK, "VISIBLE_SIGNALS", "HAVEN.STRONG", 1),
                rowV(WEEK, "SHADOW_PICTURE", "SAME", "rules-2", 3),
                rowV(WEEK, "SHADOW_PICTURE", "LESS_CAUTIOUS", "rules-2", 1));
        List<Rate> rates = EvaluationReport.rates(rows);
        Rate share = find(rates, "CONFIRMED_SHARE_OF_VISIBLE", "-");
        assertEquals(2, share.numerator());
        assertEquals(5, share.denominator(), "the older week's 9 signals are not added again");
        assertEquals(0.25, find(rates, "SHADOW_LESS_CAUTIOUS", "-").value());
        assertEquals(0.0, find(rates, "SHADOW_MORE_CAUTIOUS", "-").value());
    }

    @Test
    void gateG1_needsThreeFullFourWeekBlocks_andThreeModerators() {
        List<Row> rows = new ArrayList<>();
        for (int w = 1; w <= 12; w++) {
            rows.add(row(WEEK.minusWeeks(w), "CASE_CLOSED", "FLAGS.DISMISSED", 3));
            rows.add(rowV(WEEK.minusWeeks(w), "CASE_CLOSED", "SUPPORT_CORE.REMOVED", "coord-1", 2));
            rows.add(row(WEEK.minusWeeks(w), "CASE_CLOSED", "REREPORTED.REMOVED", 50)); // not part of G1
        }
        rows.add(row(WEEK, "CASE_CLOSED", "FLAGS.REMOVED", 100)); // the current, incomplete week never counts
        Gate met = EvaluationReport.g1(rows, WEEK, 3);
        assertEquals(List.of(20L, 20L, 20L), met.decidedCasesPerFourWeeks());
        assertEquals("COUNTS_MET_PRIVACY_REVIEW_REQUIRED", met.status(), "software never claims the review was done");

        assertEquals("NOT_MET", EvaluationReport.g1(rows, WEEK, 2).status(), "two moderators are not enough");
        rows.removeIf(r -> r.weekStart().equals(WEEK.minusWeeks(12)) && r.dimension().equals("FLAGS.DISMISSED"));
        Gate short1 = EvaluationReport.g1(rows, WEEK, 5);
        assertEquals(List.of(17L, 20L, 20L), short1.decidedCasesPerFourWeeks(), "oldest block first");
        assertEquals("NOT_MET", short1.status());
        assertEquals("NOT_MET", EvaluationReport.g1(List.of(), WEEK, 0).status());
    }

    @Test
    void theWeek_isTheMondayInUtc() {
        MutableClock clock = new MutableClock();
        LocalDate week = OutcomeRecorder.weekOf(clock);
        assertEquals(DayOfWeek.MONDAY, week.getDayOfWeek());
        assertFalse(week.isAfter(LocalDate.now(java.time.ZoneOffset.UTC)));
    }

    @Test
    void onlyVocabularyCanBeStored() {
        assertTrue(OutcomeRecorder.storable("CONDITION.EXPIRED_CONFIRMED", "-"));
        assertTrue(OutcomeRecorder.storable("SUPPORT_CORE.REMOVED", "coord-1"));
        assertTrue(OutcomeRecorder.storable("TAKE_CARE", "rules-1.1"));
        assertFalse(OutcomeRecorder.storable("tdr1v9q", "-"), "a geohash cannot be a dimension");
        assertFalse(OutcomeRecorder.storable("TDR1V9Q", "-"), "nor upper-cased (digits)");
        assertFalse(OutcomeRecorder.storable("A.B.C", "-"), "two parts at most");
        assertFalse(OutcomeRecorder.storable("USER 42", "-"));
        assertFalse(OutcomeRecorder.storable("-", "rules"));
        assertFalse(OutcomeRecorder.storable("-", "tdr1v9q"));
        assertFalse(OutcomeRecorder.storable("-", "Rules-2"));
        assertFalse(OutcomeRecorder.storable(null, "-"));
    }

    private static Rate find(List<Rate> rates, String name, String dimension) {
        return rates.stream().filter(r -> r.name().equals(name) && r.dimension().equals(dimension)).findFirst()
                .orElseThrow();
    }

    private static Row row(LocalDate week, String metric, String dimension, int value) {
        return rowV(week, metric, dimension, "-", value);
    }

    private static Row rowV(LocalDate week, String metric, String dimension, String version, int value) {
        return new Row(week, metric, dimension, version, value);
    }
}
