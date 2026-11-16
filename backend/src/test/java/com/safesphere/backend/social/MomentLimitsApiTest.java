package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase N4-B1 (PHASE_N4 §3, §5): the real daily limits: a new member shares up to 3 moments a day, an established
 * member up to 10; the new-member period is 7 days from memberships.verified_at, computed on every request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
// Its own in-memory database: a second Spring context on the shared one would drop and re-create the tables under the
// cached main context (the ProbationApiIntegrationTest finding).
@TestPropertySource(properties = {"app.moments.per-day=10", "app.moments.new-member-per-day=3",
        "app.moments.today-reads-per-hour=5",
        "spring.datasource.url=jdbc:h2:mem:safesphere-moment-limits-test;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class MomentLimitsApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private int share(MomentClient moments, Person who, String text) throws Exception {
        return moments.create(who, MomentClient.body("SAY", "COMMUNITY", text)).andReturn().getResponse().getStatus();
    }

    @Test
    void aNewMember_sharesThreeADay_anEstablishedMemberTen() throws Exception {
        clock.reset();
        SocialClient social = new SocialClient(mockMvc, objectMapper, users);
        MomentClient moments = new MomentClient(mockMvc, objectMapper);
        Person fresh = social.person("Fresh");

        // A refused request does not use up the allowance.
        moments.create(fresh, MomentClient.body("SAY", null, "No audience")).andExpect(status().isBadRequest());
        for (int i = 1; i <= 3; i++) assertThat(share(moments, fresh, "New member " + i)).isEqualTo(201);
        moments.create(fresh, MomentClient.body("SAY", "COMMUNITY", "A fourth"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(
                        "New members can share 3 moments a day in their first week. Please try again tomorrow."));
        // Deleting one does not give the place back.
        String first = moments.json(moments.mine(fresh)).get("current").get(0).get("id").asText();
        moments.delete_(fresh, first).andExpect(status().isNoContent());
        assertThat(share(moments, fresh, "After a delete")).isEqualTo(429);

        // The next day: three more.
        clock.advance(Duration.ofDays(1).plusMinutes(1));
        for (int i = 1; i <= 3; i++) assertThat(share(moments, fresh, "Day two " + i)).isEqualTo(201);
        assertThat(share(moments, fresh, "Day two, a fourth")).isEqualTo(429);

        // Established at exactly verified_at + 7 days: ten a day from then on (no role, pause or badge changes that).
        clock.advance(Duration.ofDays(7));
        for (int i = 1; i <= 10; i++) assertThat(share(moments, fresh, "Established " + i)).isEqualTo(201);
        moments.create(fresh, MomentClient.body("SAY", "COMMUNITY", "An eleventh"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("You have shared a lot today. Please try again tomorrow."));
    }

    @Test
    void thePeriod_isCountedFromTheApprovalOfTheMembership_notFromTheAccount() throws Exception {
        clock.reset();
        SocialClient social = new SocialClient(mockMvc, objectMapper, users);
        MomentClient moments = new MomentClient(mockMvc, objectMapper);
        Person member = social.person("Member");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        // An old account, approved 6 days and 23 hours ago: still a new member.
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", now.minusDays(400), member.id());
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?",
                now.minusDays(7).plusHours(1), now.minusDays(7).plusHours(1), member.id());
        for (int i = 1; i <= 3; i++) assertThat(share(moments, member, "Still new " + i)).isEqualTo(201);
        assertThat(share(moments, member, "Still new, a fourth")).isEqualTo(429);

        // Two hours later the 7 days are over: established, and the three already shared today count towards the ten.
        clock.advance(Duration.ofHours(2));
        for (int i = 4; i <= 10; i++) assertThat(share(moments, member, "Established " + i)).isEqualTo(201);
        assertThat(share(moments, member, "An eleventh")).isEqualTo(429);

        // A new verification (for example for a PG programme) starts a new 7 days.
        clock.advance(Duration.ofDays(2));
        LocalDateTime again = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", again, again,
                member.id());
        for (int i = 1; i <= 3; i++) assertThat(share(moments, member, "New again " + i)).isEqualTo(201);
        assertThat(share(moments, member, "New again, a fourth")).isEqualTo(429);
    }

    @Test
    void today_cannotBeScraped() throws Exception {
        clock.reset();
        SocialClient social = new SocialClient(mockMvc, objectMapper, users);
        MomentClient moments = new MomentClient(mockMvc, objectMapper);
        Person reader = social.person("Reader");
        for (int i = 0; i < 5; i++) moments.today(reader, null, null).andExpect(status().isOk());
        moments.today(reader, null, null).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        moments.today(reader, null, null).andExpect(status().isOk());
    }
}
