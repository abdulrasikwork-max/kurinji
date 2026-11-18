package com.safesphere.backend.circlealert;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.SkipReason;
import com.safesphere.backend.circlealert.service.AlertPolicy;
import com.safesphere.backend.circlealert.service.AlertPolicy.Facts;
import com.safesphere.backend.circlealert.service.AlertPolicy.Planned;
import com.safesphere.backend.circlealert.service.AlertPolicy.Position;
import com.safesphere.backend.circlealert.service.AlertPolicy.Recipient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** N6 (D20, docs/PHASE_N6.md §3 and §7): the pure rules of the automatic Safety Circle texts. */
class AlertPolicyTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 18, 16, 11, 0);
    /** 16:11 UTC = 9:41 PM in India. */
    private static final Instant AT = Instant.parse("2026-11-18T16:11:00Z");
    private static final Position HERE = new Position(new BigDecimal("10.812345"), new BigDecimal("78.690123"));
    private static final String PHONE = "+919876543210";

    // ---- timing ------------------------------------------------------------------------------------------------------

    @Test
    void theFirstText_goesAtTheFirstLocation_orAfter20Seconds_whicheverComesFirst() {
        LocalDateTime due = AlertPolicy.firstDue(T0, Duration.ofSeconds(20));
        assertThat(due).isEqualTo(T0.plusSeconds(20));
        assertThat(AlertPolicy.due(Kind.SOS_STARTED, due, 0, false, T0.plusSeconds(5))).isFalse();
        assertThat(AlertPolicy.due(Kind.SOS_STARTED, due, 0, true, T0.plusSeconds(5))).isTrue();
        assertThat(AlertPolicy.due(Kind.SOS_STARTED, due, 0, false, T0.plusSeconds(20))).isTrue();
        // a retry waits for its own time even when a location exists
        assertThat(AlertPolicy.due(Kind.SOS_STARTED, T0.plusSeconds(60), 1, true, T0.plusSeconds(30))).isFalse();
        assertThat(AlertPolicy.due(Kind.SOS_LOCATION, T0.plusSeconds(60), 0, true, T0.plusSeconds(30))).isFalse();
    }

    @Test
    void anSosOlderThan30Minutes_isNotTexted_andOneOlderThan2Minutes_isSentLate() {
        Duration stale = Duration.ofMinutes(30);
        assertThat(AlertPolicy.tooLate(T0, T0.plusMinutes(30), stale)).isFalse();
        assertThat(AlertPolicy.tooLate(T0, T0.plusMinutes(30).plusSeconds(1), stale)).isTrue();
        assertThat(AlertPolicy.late(T0, T0.plusSeconds(3), Duration.ofMinutes(2))).isFalse();
        assertThat(AlertPolicy.late(T0, T0.plusMinutes(10), Duration.ofMinutes(2))).isTrue();
    }

    @Test
    void oneFollowUp_onlyAfterATextWithoutLocation_onlyWithin10Minutes_onlyWhileActive() {
        Duration window = Duration.ofMinutes(10);
        LocalDateTime now = T0.plusMinutes(3);
        assertThat(AlertPolicy.followUpDue(false, T0, true, true, false, window, now)).isTrue();
        assertThat(AlertPolicy.followUpDue(true, T0, true, true, false, window, now)).as("had one").isFalse();
        assertThat(AlertPolicy.followUpDue(false, T0, false, true, false, window, now)).as("no place yet").isFalse();
        assertThat(AlertPolicy.followUpDue(false, T0, true, false, false, window, now)).as("ended").isFalse();
        assertThat(AlertPolicy.followUpDue(false, T0, true, true, true, window, now)).as("only once").isFalse();
        assertThat(AlertPolicy.followUpDue(false, T0, true, true, false, window, T0.plusMinutes(11))).isFalse();
    }

    @Test
    void aClosingText_onlyAfterAnAlertWentOut_onlyOnce_andNeverForAnSosEndedElsewhere() {
        assertThat(AlertPolicy.closingDue(true, false, false)).isTrue();
        assertThat(AlertPolicy.closingDue(false, false, false)).as("false alarm before sending: nothing").isFalse();
        assertThat(AlertPolicy.closingDue(true, true, false)).as("ended from another phone").isFalse();
        assertThat(AlertPolicy.closingDue(true, false, true)).as("once").isFalse();
    }

    @Test
    void refusals_retryWithGrowingWaits_thenStop() {
        Duration delay = Duration.ofSeconds(15);
        assertThat(AlertPolicy.retryAt(1, 3, delay, T0)).isEqualTo(T0.plusSeconds(15));
        assertThat(AlertPolicy.retryAt(2, 3, delay, T0)).isEqualTo(T0.plusSeconds(30));
        assertThat(AlertPolicy.retryAt(3, 3, delay, T0)).isNull();
    }

    // ---- who ---------------------------------------------------------------------------------------------------------

    @Test
    void indianMobilesOnly_eachNumberOnce_neverAStoppedOne_andAtMostFive() {
        List<Recipient> inOrder = new ArrayList<>();
        inOrder.add(new Recipient(1, "+919000000001", "k1"));
        inOrder.add(new Recipient(2, null, null));              // a landline or a foreign number
        inOrder.add(new Recipient(3, "+919000000001", "k1"));   // the same number again
        inOrder.add(new Recipient(4, "+919000000004", "stop")); // asked IRL for no alerts
        for (int i = 5; i <= 10; i++) inOrder.add(new Recipient(i, "+91900000001" + i % 10, "k" + i));

        List<Planned> plan = AlertPolicy.plan(inOrder, Set.of("stop")::contains, 5);

        assertThat(plan).extracting(Planned::reason).containsExactly(null, SkipReason.NOT_INDIAN_MOBILE,
                SkipReason.DUPLICATE_NUMBER, SkipReason.STOPPED, null, null, null, null,
                SkipReason.NOT_IN_FIRST_FIVE, SkipReason.NOT_IN_FIRST_FIVE);
        assertThat(plan.stream().filter(Planned::texted).count()).isEqualTo(5);
    }

    @Test
    void theLimits_areCheckedInOrder() {
        assertThat(AlertPolicy.overLimit(false, 9, 10, 99, 100)).isNull();
        assertThat(AlertPolicy.overLimit(true, 0, 10, 0, 100)).isEqualTo(SkipReason.STOPPED);
        assertThat(AlertPolicy.overLimit(false, 10, 10, 0, 100)).isEqualTo(SkipReason.NUMBER_LIMIT);
        assertThat(AlertPolicy.overLimit(false, 0, 10, 100, 100)).isEqualTo(SkipReason.SERVER_LIMIT);
    }

    // ---- what --------------------------------------------------------------------------------------------------------

    @Test
    void theFirstText_matchesTheDesign_andCarriesOnlyAllowListedFacts() {
        String text = AlertPolicy.text(Kind.SOS_STARTED, new Facts("Asha Devi", PHONE, AT, HERE, false));
        assertThat(text).isEqualTo("IRL SOS: Asha Devi pressed SOS at 9:41 PM. Location: maps.google.com/?q=10.81235,"
                + "78.69012 Call 9876543210 now. No answer? Call 112.");
        String noPlace = AlertPolicy.text(Kind.SOS_STARTED, new Facts("Asha Devi", PHONE, AT, null, true));
        assertThat(noPlace).startsWith("IRL SOS (sent late): Asha Devi").contains("Location not known yet.")
                .doesNotContain("maps.");
    }

    @Test
    void everyText_isOnePlainAsciiPart_forNamesUpTo30Characters_andTheNameIsTheOnlyThingShortened() {
        String name30 = "Abcdefghij Klmnopqrst Uvwxyzabc"; // 31 with spaces; a long real name
        String name60 = "Abcdefghijklmnopqrstuvwxyz Abcdefghijklmnopqrstuvwxyz Abcdefgh";
        for (Kind kind : List.of(Kind.SOS_STARTED, Kind.SOS_LOCATION, Kind.SOS_RESOLVED, Kind.SOS_CANCELLED, Kind.TEST)) {
            for (boolean late : List.of(false, true)) {
                String text = AlertPolicy.text(kind, new Facts(name30, PHONE, AT, HERE, late));
                assertThat(text.length()).as(kind + " " + late).isLessThanOrEqualTo(AlertPolicy.ONE_PART);
                assertThat(text.chars().allMatch(c -> c >= 32 && c < 127)).as("ASCII").isTrue();
                if (kind != Kind.TEST) assertThat(text).contains(name30.substring(0, 10)).contains("9876543210");
                String longName = AlertPolicy.text(kind, new Facts(name60, PHONE, AT, HERE, late));
                assertThat(longName.length()).as("shortened " + kind).isLessThanOrEqualTo(AlertPolicy.ONE_PART);
                if (kind != Kind.TEST) assertThat(longName).contains("9876543210").contains("9:41 PM");
            }
        }
    }

    @Test
    void theOtherTexts_sayWhatHappened_withoutClaimingAllIsWell() {
        Facts f = new Facts("Asha Devi", PHONE, AT, HERE, false);
        assertThat(AlertPolicy.text(Kind.SOS_LOCATION, f)).startsWith("IRL SOS update: Asha Devi was here at 9:41 PM: "
                + "maps.google.com/?q=10.81235,78.69012");
        assertThat(AlertPolicy.text(Kind.SOS_RESOLVED, f)).isEqualTo("IRL: Asha Devi ended the SOS at 9:41 PM and marked"
                + " it I'm safe. Call 9876543210 if you are worried.");
        assertThat(AlertPolicy.text(Kind.SOS_CANCELLED, f)).contains("as a false alarm").contains("if you are worried");
        for (Kind kind : List.of(Kind.SOS_RESOLVED, Kind.SOS_CANCELLED)) {
            assertThat(AlertPolicy.text(kind, f).toLowerCase()).doesNotContain("all is well").doesNotContain("is safe")
                    .doesNotContain("maps.");
        }
        assertThat(AlertPolicy.testText()).contains("not an emergency").doesNotContain("maps.google.com/?q=");
    }

    @Test
    void names_becomePlainAscii_andANameWithoutLatinLetters_becomesYourContact() {
        Facts tamil = new Facts("அருண்", PHONE, AT, HERE, false);
        assertThat(AlertPolicy.text(Kind.SOS_STARTED, tamil)).startsWith("IRL SOS: Your contact pressed SOS")
                .contains("9876543210");
        Facts accented = new Facts("Zoé O’Brien", PHONE, AT, HERE, false);
        assertThat(AlertPolicy.text(Kind.SOS_STARTED, accented)).startsWith("IRL SOS: Zoe O'Brien pressed SOS");
        Facts sneaky = new Facts("Ravi http://x.y/z 123", PHONE, AT, HERE, false);
        assertThat(AlertPolicy.text(Kind.SOS_STARTED, sneaky)).startsWith("IRL SOS: Ravi httpx.yz pressed") // no link can be built from a name
                .doesNotContain("http://").doesNotContain("123 pressed");
    }

    @Test
    void thePreview_isTheExactFirstText_withThePlaceShownAsSuch() {
        String preview = AlertPolicy.preview("Asha Devi", PHONE, AT);
        assertThat(preview).isEqualTo("IRL SOS: Asha Devi pressed SOS at 9:41 PM. Location: maps.google.com/?q=(where you"
                + " are) Call 9876543210 now. No answer? Call 112.");
    }
}
