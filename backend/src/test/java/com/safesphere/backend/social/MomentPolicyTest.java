package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;

import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.policy.MomentPolicy.Draft;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * Phase N4: the locked rules of a moment (docs/PHASE_N4.md §2, §3, §5), without a database: its shape, the Who's in?
 * window, the new-member period to the microsecond, the 24-hour life that starts at first visibility, keep, and purge.
 */
class MomentPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0);

    private static Draft say(String text) {
        return new Draft(Kind.SAY, Audience.COMMUNITY, text, false, null, null);
    }

    @Test
    void theLockedNumbers_areTheOwnersDecisions() {
        assertThat(MomentPolicy.TEXT_MAX).isEqualTo(500);
        assertThat(MomentPolicy.WHERE_MAX).isEqualTo(60);
        assertThat(MomentPolicy.MAX_KEPT).isEqualTo(6);
        assertThat(MomentPolicy.LIFE).isEqualTo(Duration.ofHours(24));
        assertThat(MomentPolicy.PURGE_AFTER).isEqualTo(Duration.ofDays(7));
        assertThat(MomentPolicy.PLAN_WINDOW).isEqualTo(Duration.ofHours(24));
        assertThat(MomentPolicy.NEW_MEMBER_PERIOD).isEqualTo(Duration.ofDays(7));
        assertThat(Kind.values()).containsExactly(Kind.SAY, Kind.WHOS_IN);           // no voice, no video (N-44)
        assertThat(Audience.values()).containsExactly(Audience.COMMUNITY, Audience.MY_PEOPLE);
    }

    @Test
    void aMoment_needsAKind_anAudience_andTextOrAPhoto() {
        assertThat(MomentPolicy.check(say("On my mind"), NOW)).isEmpty();
        assertThat(MomentPolicy.check(new Draft(null, Audience.COMMUNITY, "x", false, null, null), NOW))
                .contains("Choose Say or Who's in?");
        // N-46: there is no default audience.
        assertThat(MomentPolicy.check(new Draft(Kind.SAY, null, "x", false, null, null), NOW))
                .contains("Choose who can see this");
        assertThat(MomentPolicy.check(say(null), NOW)).contains("Write something or add a photo");
        // N-45: the text is optional next to a photo.
        assertThat(MomentPolicy.check(new Draft(Kind.SAY, Audience.MY_PEOPLE, null, true, null, null), NOW)).isEmpty();
    }

    @Test
    void text_hasAtMost500Characters_countedAsPeopleCountThem() {
        assertThat(MomentPolicy.check(say("a".repeat(500)), NOW)).isEmpty();
        assertThat(MomentPolicy.check(say("a".repeat(501)), NOW)).contains("A moment has at most 500 characters");
        // Tamil letters and an emoji outside the basic plane are one character each (code points, like V35's CHECK).
        String tamil = "த".repeat(499) + "😀";
        assertThat(tamil.length()).isEqualTo(501);
        assertThat(MomentPolicy.length(tamil)).isEqualTo(500);
        assertThat(MomentPolicy.check(say(tamil), NOW)).isEmpty();
    }

    @Test
    void onlyAWhosIn_hasATimeAndAWhere_withinTheNext24Hours() {
        Draft plan = new Draft(Kind.WHOS_IN, Audience.COMMUNITY, "Chai?", false, NOW.plusHours(2), "Canteen");
        assertThat(MomentPolicy.check(plan, NOW)).isEmpty();
        // Both are optional.
        assertThat(MomentPolicy.check(new Draft(Kind.WHOS_IN, Audience.COMMUNITY, "Chai?", false, null, null), NOW))
                .isEmpty();
        // A Say has neither.
        assertThat(MomentPolicy.check(new Draft(Kind.SAY, Audience.COMMUNITY, "x", false, NOW.plusHours(1), null), NOW))
                .contains("Only a Who's in? has a time and a place");
        assertThat(MomentPolicy.check(new Draft(Kind.SAY, Audience.COMMUNITY, "x", false, null, "Canteen"), NOW))
                .contains("Only a Who's in? has a time and a place");
        // The window: after now, at most 24 hours ahead (the end is included).
        assertThat(MomentPolicy.check(plan(NOW), NOW)).contains("Choose a time within the next 24 hours");
        assertThat(MomentPolicy.check(plan(NOW.minusMinutes(1)), NOW)).contains("Choose a time within the next 24 hours");
        assertThat(MomentPolicy.check(plan(NOW.plusHours(24)), NOW)).isEmpty();
        assertThat(MomentPolicy.check(plan(NOW.plusHours(24).plusMinutes(1)), NOW))
                .contains("Choose a time within the next 24 hours");
        // Where: at most 60 characters.
        assertThat(MomentPolicy.check(new Draft(Kind.WHOS_IN, Audience.COMMUNITY, "x", false, null, "w".repeat(60)), NOW))
                .isEmpty();
        assertThat(MomentPolicy.check(new Draft(Kind.WHOS_IN, Audience.COMMUNITY, "x", false, null, "w".repeat(61)), NOW))
                .contains("Where has at most 60 characters");
    }

    private static Draft plan(LocalDateTime at) {
        return new Draft(Kind.WHOS_IN, Audience.COMMUNITY, "Chai?", false, at, null);
    }

    @Test
    void theNewMemberPeriod_endsAtExactlySevenDaysAfterApproval() {
        // N-56: counted from memberships.verified_at; microseconds are what the database stores.
        LocalDateTime verifiedAt = LocalDateTime.of(2026, 11, 16, 9, 30, 15, 123_456_000);
        LocalDateTime established = verifiedAt.plusDays(7);
        assertThat(MomentPolicy.establishedAt(verifiedAt)).isEqualTo(established);
        assertThat(MomentPolicy.isNewMember(verifiedAt, verifiedAt)).isTrue();
        assertThat(MomentPolicy.isNewMember(verifiedAt, established.minus(1, ChronoUnit.MICROS))).isTrue();   // one µs before
        assertThat(MomentPolicy.isNewMember(verifiedAt, established)).isFalse();                              // exactly at
        assertThat(MomentPolicy.isNewMember(verifiedAt, established.plus(1, ChronoUnit.MICROS))).isFalse();   // just after
        assertThat(MomentPolicy.isNewMember(verifiedAt, established.plusYears(1))).isFalse();
        // A new verification starts a new period: only the latest approval time counts.
        LocalDateTime again = established.plusDays(30);
        assertThat(MomentPolicy.isNewMember(again, again.plusDays(6))).isTrue();
    }

    @Test
    void newMembers_shareNoLinks_theRuleFindsThemInTextAndWhere() {
        assertThat(MomentPolicy.containsLink("see https://example.org now")).isTrue();
        assertThat(MomentPolicy.containsLink("join at www.example.org")).isTrue();
        assertThat(MomentPolicy.containsLink(null, "my-club.in tonight")).isTrue();
        assertThat(MomentPolicy.containsLink("Chai at 4. Canteen.", null)).isFalse();
        assertThat(MomentPolicy.containsLink("இன்று மாலை")).isFalse();
    }

    @Test
    void cleaning_keepsLineBreaks_andRemovesWhatCannotBeSeen() {
        assertThat(MomentPolicy.cleanText("  Hello\r\n\r\n\r\n\r\nworld ​\u0000 ")).isEqualTo("Hello\n\nworld");
        assertThat(MomentPolicy.cleanText("a \t  b")).isEqualTo("a b");
        assertThat(MomentPolicy.cleanText("   \n ​ ")).isNull();
        assertThat(MomentPolicy.cleanText(null)).isNull();
        // One line of plain text: a line break becomes a space.
        assertThat(MomentPolicy.cleanLine(" Main\ncanteen ")).isEqualTo("Main canteen");
        assertThat(MomentPolicy.cleanLine("  ")).isNull();
    }

    // ---- The life of a moment (N-47) ----

    @Test
    void aMoment_livesFor24Hours_fromWhenOthersCanFirstSeeIt() {
        Moment moment = new Moment(1, 1, Kind.SAY, Audience.COMMUNITY, "Hi", NOW);
        // Not published: nobody else can see it, and it has no end to run towards.
        assertThat(moment.getVisibleAt()).isNull();
        assertThat(moment.getExpiresAt()).isNull();
        assertThat(moment.hasStarted(NOW.plusDays(3))).isFalse();
        assertThat(moment.isLive(NOW.plusDays(3))).isFalse();
        assertThat(moment.hasEnded(NOW.plusDays(3))).isFalse();

        // A moderator releases it 5 hours later: the 24 hours start THEN, not when it was written.
        LocalDateTime released = NOW.plusHours(5);
        moment.publish(released, MomentPolicy.LIFE);
        assertThat(moment.getVisibleAt()).isEqualTo(released);
        assertThat(moment.getExpiresAt()).isEqualTo(released.plusHours(24));
        assertThat(moment.isLive(released)).isTrue();
        assertThat(moment.isLive(released.plusHours(24).minus(1, ChronoUnit.MICROS))).isTrue();
        assertThat(moment.isLive(released.plusHours(24))).isFalse();                 // gone at exactly 24 hours
        assertThat(moment.hasEnded(released.plusHours(24))).isTrue();

        // Only the first time counts: hidden and shown again later, it keeps the end it had.
        moment.publish(released.plusHours(10), MomentPolicy.LIFE);
        assertThat(moment.getVisibleAt()).isEqualTo(released);
        assertThat(moment.getExpiresAt()).isEqualTo(released.plusHours(24));
    }

    @Test
    void aMomentSharedNormally_startsAtOnce() {
        Moment moment = new Moment(1, 1, Kind.SAY, Audience.COMMUNITY, "Hi", NOW);
        moment.publish(NOW, MomentPolicy.LIFE);
        assertThat(moment.getVisibleAt()).isEqualTo(NOW);
        assertThat(moment.getExpiresAt()).isEqualTo(NOW.plusHours(24));
        assertThat(moment.hasStarted(NOW.minusSeconds(1))).isFalse();
    }

    @Test
    void keep_andUnkeep() {
        Moment moment = new Moment(1, 1, Kind.SAY, Audience.COMMUNITY, "Hi", NOW);
        moment.publish(NOW, MomentPolicy.LIFE);
        assertThat(moment.isKept()).isFalse();
        moment.keep(NOW.plusHours(1));
        assertThat(moment.isKept()).isTrue();
        assertThat(moment.getKeptAt()).isEqualTo(NOW.plusHours(1));
        moment.keep(NOW.plusHours(2));                                               // already kept: nothing moves
        assertThat(moment.getKeptAt()).isEqualTo(NOW.plusHours(1));
        moment.unkeep();
        assertThat(moment.isKept()).isFalse();
    }

    @Test
    void purge_isDueMoreThanSevenDaysAfterTheEnd_andNeverForAMomentThatWasNeverVisible() {
        LocalDateTime end = NOW;
        assertThat(MomentPolicy.purgeDue(end, end.plusDays(7).minusSeconds(1))).isFalse();
        assertThat(MomentPolicy.purgeDue(end, end.plusDays(7))).isFalse();
        assertThat(MomentPolicy.purgeDue(end, end.plusDays(7).plus(1, ChronoUnit.MICROS))).isTrue();
        assertThat(MomentPolicy.purgeDue(null, end.plusYears(5))).isFalse();         // N-57: waiting for a human
    }

    @Test
    void onlyAWhosIn_canCarryAPlan_inTheEntityToo() {
        Moment say = new Moment(1, 1, Kind.SAY, Audience.COMMUNITY, "Hi", NOW);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> say.plan(NOW.plusHours(1), null));
        Moment plan = new Moment(1, 1, Kind.WHOS_IN, Audience.MY_PEOPLE, "Chai?", NOW);
        plan.plan(NOW.plusHours(1), "Canteen");
        assertThat(plan.getPlanAt()).isEqualTo(NOW.plusHours(1));
        assertThat(plan.getPlanWhere()).isEqualTo("Canteen");
    }
}
