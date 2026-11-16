package com.safesphere.backend.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.social.entity.Moment;
import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import com.safesphere.backend.social.entity.PostEnums.ModerationReason;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase N4-B1: moments through the real API: sharing a Say and a Who's in?, who sees what (author, connection, member
 * who is not connected, blocked, muted, not a member), rough times instead of timestamps, Today (order, pages, the
 * My people switch), the 24-hour life, keep, delete, and a moment that waits for a human.
 *
 * The test database is shared by every test class and MIET is one wall, so Today may also hold other tests' moments:
 * the assertions here are about the moments each test made.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class MomentApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MomentRepository momentRows;

    private SocialClient social;
    private MomentClient moments;
    private Person asha;
    private Person bala;      // connected to Asha (one of her people)
    private Person chitra;    // a member, not connected

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        social.connect(asha, bala);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- Sharing ----

    @Test
    void aSay_isLiveAtOnce_theAuthorGetsExactTimes_othersOnlyARoughOne() throws Exception {
        JsonNode own = moments.json(moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "  Exams are over!\r\n\r\n\r\n\r\nFinally.  "))
                .andExpect(status().isCreated()));
        assertThat(own.get("kind").asText()).isEqualTo("SAY");
        assertThat(own.get("audience").asText()).isEqualTo("COMMUNITY");
        assertThat(own.get("text").asText()).isEqualTo("Exams are over!\n\nFinally.");
        assertThat(own.get("mine").asBoolean()).isTrue();
        assertThat(own.get("status").asText()).isEqualTo("LIVE");
        assertThat(own.get("kept").asBoolean()).isFalse();
        assertThat(own.get("plan").isNull()).isTrue();
        assertThat(own.get("question").isNull()).isTrue();
        assertThat(own.get("author").get("profileId").asText()).isEqualTo(asha.publicId());
        assertThat(own.get("author").get("handle").asText()).isEqualTo(asha.handle());
        assertThat(own.get("author").get("name").asText()).isEqualTo("Test User");
        // N-47: 24 hours from when others can first see it.
        Instant visibleAt = Instant.parse(own.get("visibleAt").asText());
        assertThat(Instant.parse(own.get("createdAt").asText())).isEqualTo(visibleAt);
        assertThat(Instant.parse(own.get("expiresAt").asText())).isEqualTo(visibleAt.plus(Duration.ofHours(24)));
        assertThat(own.get("when").get("day").asText()).isEqualTo("TODAY");

        String id = own.get("id").asText();
        for (Person viewer : List.of(bala, chitra)) {
            JsonNode seen = moments.json(moments.get_(viewer, id).andExpect(status().isOk()));
            assertThat(seen.get("mine").asBoolean()).isFalse();
            assertThat(seen.get("text").asText()).isEqualTo("Exams are over!\n\nFinally.");
            assertThat(seen.get("when").get("day").asText()).isEqualTo("TODAY");
            assertThat(seen.get("when").get("part").asText()).isIn("MORNING", "AFTERNOON", "EVENING", "NIGHT");
            // Never a timestamp for anyone but the author.
            assertThat(seen.get("createdAt").isNull()).isTrue();
            assertThat(seen.get("visibleAt").isNull()).isTrue();
            assertThat(seen.get("expiresAt").isNull()).isTrue();
        }
    }

    @Test
    void anAnswer_carriesNoCount_noLocation_noViewerAndNoPresenceField() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Just the fields");
        JsonNode seen = moments.json(moments.get_(chitra, id).andExpect(status().isOk()));
        List<String> names = new ArrayList<>();
        seen.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "kind", "audience", "author", "mine", "text", "photo", "plan",
                "question", "when", "status", "kept", "createdAt", "visibleAt", "expiresAt");
        List<String> author = new ArrayList<>();
        seen.get("author").fieldNames().forEachRemaining(author::add);
        assertThat(author).containsExactlyInAnyOrder("profileId", "handle", "name", "photo");
        JsonNode today = moments.json(moments.today(chitra, null, null).andExpect(status().isOk()));
        List<String> page = new ArrayList<>();
        today.fieldNames().forEachRemaining(page::add);
        assertThat(page).containsExactlyInAnyOrder("question", "moments", "nextCursor");
    }

    @Test
    void aWhosIn_showsItsTimeExactly_andItsWhereAsWritten() throws Exception {
        Instant at = clock.instant().plus(Duration.ofHours(2));
        Map<String, Object> body = MomentClient.body("WHOS_IN", "COMMUNITY", "Chai after lab?");
        body.put("planAt", at.toString());
        body.put("planWhere", "  Main  canteen ");
        JsonNode own = moments.json(moments.create(asha, body).andExpect(status().isCreated()));
        assertThat(own.get("kind").asText()).isEqualTo("WHOS_IN");
        assertThat(Instant.parse(own.get("plan").get("at").asText())).isEqualTo(at.truncatedTo(ChronoUnit.MINUTES));
        assertThat(own.get("plan").get("where").asText()).isEqualTo("Main canteen");

        // The plan's time is the author's own words to the community: everyone who sees the moment gets it exactly.
        JsonNode seen = moments.json(moments.get_(chitra, own.get("id").asText()).andExpect(status().isOk()));
        assertThat(Instant.parse(seen.get("plan").get("at").asText())).isEqualTo(at.truncatedTo(ChronoUnit.MINUTES));
        assertThat(seen.get("plan").get("where").asText()).isEqualTo("Main canteen");
        assertThat(seen.get("createdAt").isNull()).isTrue();

        // Both parts are optional.
        JsonNode bare = moments.json(moments.create(asha, MomentClient.body("WHOS_IN", "MY_PEOPLE", "Movie tonight?"))
                .andExpect(status().isCreated()));
        assertThat(bare.get("plan").get("at").isNull()).isTrue();
        assertThat(bare.get("plan").get("where").isNull()).isTrue();
    }

    @Test
    void aPlan_mustBeWithinTheNext24Hours_andOnlyOnAWhosIn() throws Exception {
        Map<String, Object> past = MomentClient.body("WHOS_IN", "COMMUNITY", "Chai?");
        past.put("planAt", clock.instant().minus(Duration.ofMinutes(5)).toString());
        moments.create(asha, past).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a time within the next 24 hours"));

        Map<String, Object> far = MomentClient.body("WHOS_IN", "COMMUNITY", "Chai?");
        far.put("planAt", clock.instant().plus(Duration.ofHours(25)).toString());
        moments.create(asha, far).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a time within the next 24 hours"));

        Map<String, Object> garbage = MomentClient.body("WHOS_IN", "COMMUNITY", "Chai?");
        garbage.put("planAt", "tomorrow evening");
        moments.create(asha, garbage).andExpect(status().isBadRequest());

        Map<String, Object> longWhere = MomentClient.body("WHOS_IN", "COMMUNITY", "Chai?");
        longWhere.put("planWhere", "w".repeat(61));
        moments.create(asha, longWhere).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Where has at most 60 characters"));

        Map<String, Object> say = MomentClient.body("SAY", "COMMUNITY", "Not a plan");
        say.put("planWhere", "Canteen");
        moments.create(asha, say).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a Who's in? has a time and a place"));
    }

    @Test
    void aMoment_needsAKind_aChosenAudience_andUpTo500Characters() throws Exception {
        moments.create(asha, MomentClient.body("SAY", null, "No audience"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Choose who can see this"));
        moments.create(asha, MomentClient.body("SAY", "EVERYONE", "The old audience"))
                .andExpect(status().isBadRequest());
        moments.create(asha, MomentClient.body(null, "COMMUNITY", "No kind")).andExpect(status().isBadRequest());
        // N-44: no voice, no video; and nothing from the old social model.
        for (String kind : List.of("VOICE", "VIDEO", "EXPERIENCE", "ASK")) {
            moments.create(asha, MomentClient.body(kind, "COMMUNITY", "x")).andExpect(status().isBadRequest());
        }
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "   \n  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Write something or add a photo"));
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "a".repeat(501)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A moment has at most 500 characters"));
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "த".repeat(500))).andExpect(status().isCreated());
    }

    @Test
    void sharingNeedsNoProfile_theAuthorIsTheirRealName() throws Exception {
        String token = social.accountWithoutProfile();
        JsonNode own = moments.json(moments.create(token, MomentClient.body("SAY", "COMMUNITY", "No profile yet"))
                .andExpect(status().isCreated()));
        JsonNode seen = moments.json(moments.get_(chitra, own.get("id").asText()).andExpect(status().isOk()));
        assertThat(seen.get("author").get("name").asText()).isEqualTo("Test User");
        assertThat(seen.get("author").get("profileId").isNull()).isTrue();
        assertThat(seen.get("author").get("handle").isNull()).isTrue();
    }

    @Test
    void aNewMember_sharesNoLinks_untilSevenDaysAfterApproval() throws Exception {
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "Join at https://example.org"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MomentPolicy.NO_LINKS_FOR_NEW_MEMBERS));
        Map<String, Object> plan = MomentClient.body("WHOS_IN", "COMMUNITY", "Come along");
        plan.put("planWhere", "www.example.org");
        moments.create(asha, plan).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(MomentPolicy.NO_LINKS_FOR_NEW_MEMBERS));

        clock.advance(Duration.ofDays(7).minusMinutes(1));
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "Join at https://example.org"))
                .andExpect(status().isBadRequest());
        clock.advance(Duration.ofMinutes(2));   // established: N-56
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "Join at https://example.org"))
                .andExpect(status().isCreated());
    }

    // ---- Who sees what ----

    @Test
    void myPeople_reachesMutualConnectionsOnly() throws Exception {
        String forPeople = moments.say(asha, "MY_PEOPLE", "Only my people");
        String forAll = moments.say(asha, "COMMUNITY", "All of MIET");

        moments.get_(bala, forPeople).andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("MY_PEOPLE"));
        moments.get_(bala, forAll).andExpect(status().isOk());
        assertThat(moments.wholeToday(bala, "ALL")).contains(forPeople, forAll);
        assertThat(moments.wholeToday(bala, "MINE")).containsExactly(forAll, forPeople);

        // A member who is not connected: the community moment only; the other one does not exist for them.
        moments.get_(chitra, forPeople).andExpect(status().isNotFound());
        moments.get_(chitra, forAll).andExpect(status().isOk());
        assertThat(moments.wholeToday(chitra, "ALL")).contains(forAll).doesNotContain(forPeople);
        assertThat(moments.wholeToday(chitra, "MINE")).isEmpty();

        // The author's own wall shows both; "My people" is other people, so it does not show her own.
        assertThat(moments.wholeToday(asha, "ALL")).contains(forPeople, forAll);
        assertThat(moments.wholeToday(asha, "MINE")).doesNotContain(forPeople, forAll);

        // No longer connected: the My people moment is gone for Bala at once.
        social.removeConnection(asha, bala).andExpect(status().isNoContent());
        moments.get_(bala, forPeople).andExpect(status().isNotFound());
        moments.get_(bala, forAll).andExpect(status().isOk());
    }

    @Test
    void aBlock_hidesMomentsBothWays_aMuteOnlyOnToday() throws Exception {
        String byAsha = moments.say(asha, "COMMUNITY", "From Asha");
        String byChitra = moments.say(chitra, "COMMUNITY", "From Chitra");
        String byBala = moments.say(bala, "COMMUNITY", "From Bala");

        // A mute: gone from Today, still readable when opened deliberately; the muted person notices nothing.
        social.mute(chitra, bala).andExpect(status().isNoContent());
        assertThat(moments.wholeToday(chitra, "ALL")).contains(byAsha).doesNotContain(byBala);
        moments.get_(chitra, byBala).andExpect(status().isOk());
        assertThat(moments.wholeToday(bala, "ALL")).contains(byChitra);

        // A block: neither sees the other's moments anywhere.
        social.block(chitra, asha).andExpect(status().isNoContent());
        moments.get_(chitra, byAsha).andExpect(status().isNotFound());
        moments.get_(asha, byChitra).andExpect(status().isNotFound());
        assertThat(moments.wholeToday(chitra, "ALL")).doesNotContain(byAsha);
        assertThat(moments.wholeToday(asha, "ALL")).doesNotContain(byChitra);
    }

    @Test
    void momentsAreForActiveMembersOnly_inBothDirections() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Members only");

        // Signed in, not verified: nothing of the community, with the code the app acts on.
        String outsider = TestAccounts.registerUnverified(mockMvc, objectMapper, "Out Sider",
                "moment." + UUID.randomUUID() + "@example.com").get("accessToken").asText();
        Person notMember = new Person(outsider, 0, "-", "-");
        moments.create(outsider, MomentClient.body("SAY", "COMMUNITY", "Let me in"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBERSHIP_NEEDED"));
        moments.today(notMember, null, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_NEEDED"));
        moments.get_(notMember, id).andExpect(status().isForbidden());
        moments.mine(notMember).andExpect(status().isForbidden());
        moments.keep(notMember, id).andExpect(status().isForbidden());

        // The author's membership ends (graduated, removed or banned): her moments are gone for everyone else, and
        // she can no longer share or read; nothing else about her account is touched.
        jdbc.update("UPDATE memberships SET ended_at = ?, end_reason = 'REMOVED' WHERE user_id = ?",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), asha.id());
        moments.get_(bala, id).andExpect(status().isNotFound());
        assertThat(moments.wholeToday(bala, "ALL")).doesNotContain(id);
        assertThat(moments.wholeToday(bala, "MINE")).doesNotContain(id);
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "Still here?")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_NEEDED"));
    }

    @Test
    void aPersonWhosePostingIsPaused_cannotShare_butCanStillRead() throws Exception {
        String id = moments.say(bala, "COMMUNITY", "Readable");
        jdbc.update("INSERT INTO social_standing (user_id, restriction, restricted_until, reason, decision, decided_at)"
                        + " VALUES (?, 'POSTING_PAUSED', ?, 'SPAM', ?, ?)", asha.id(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(1), UUID.randomUUID().toString(),
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "Paused")).andExpect(status().isForbidden());
        moments.get_(asha, id).andExpect(status().isOk());
        moments.today(asha, null, null).andExpect(status().isOk());
    }

    // ---- Today ----

    @Test
    void today_isNewestFirst_inPagesOf20_andEnds() throws Exception {
        // "My people" is one viewer's own slice of the wall, so its exact content can be checked.
        List<String> shared = new ArrayList<>();
        for (int i = 1; i <= 45; i++) shared.add(moments.say(asha, i % 3 == 0 ? "MY_PEOPLE" : "COMMUNITY", "Moment " + i));
        List<String> newestFirst = new ArrayList<>(shared);
        Collections.reverse(newestFirst);

        JsonNode first = moments.json(moments.today(bala, "MINE", null).andExpect(status().isOk()));
        assertThat(MomentClient.ids(first.get("moments"))).containsExactlyElementsOf(newestFirst.subList(0, 20));
        String cursor = first.get("nextCursor").asText();
        assertThat(cursor).isEqualTo(newestFirst.get(19));

        JsonNode second = moments.json(moments.today(bala, "MINE", cursor).andExpect(status().isOk()));
        assertThat(MomentClient.ids(second.get("moments"))).containsExactlyElementsOf(newestFirst.subList(20, 40));

        JsonNode last = moments.json(moments.today(bala, "MINE", second.get("nextCursor").asText())
                .andExpect(status().isOk()));
        assertThat(MomentClient.ids(last.get("moments"))).containsExactlyElementsOf(newestFirst.subList(40, 45));
        assertThat(last.get("nextCursor").isNull()).isTrue();                    // the wall ends

        // The whole wall, for a member who is not connected: only the community ones, each once, in the same order.
        List<String> wall = moments.wholeToday(chitra, "ALL");
        assertThat(new HashSet<>(wall)).hasSize(wall.size());
        List<String> ofAsha = wall.stream().filter(shared::contains).toList();
        List<String> expected = new ArrayList<>();
        for (int i = 45; i >= 1; i--) if (i % 3 != 0) expected.add(shared.get(i - 1));
        assertThat(ofAsha).containsExactlyElementsOf(expected);

        moments.today(bala, "NEARBY", null).andExpect(status().isBadRequest());
        moments.today(bala, "MINE", UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid cursor"));
    }

    @Test
    void after24Hours_aMomentLeavesToday_andIsGoneForEveryoneElse() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Here for a day");
        clock.advance(Duration.ofHours(24).minusMinutes(1));
        assertThat(moments.wholeToday(bala, "MINE")).contains(id);
        moments.get_(bala, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));

        clock.advance(Duration.ofMinutes(2));
        assertThat(moments.wholeToday(bala, "MINE")).doesNotContain(id);
        assertThat(moments.wholeToday(asha, "ALL")).doesNotContain(id);
        moments.get_(bala, id).andExpect(status().isNotFound());
        // The author can still open it (its data remains for 7 days), marked as ended; it is not in her current list.
        moments.get_(asha, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENDED"));
        JsonNode mine = moments.json(moments.mine(asha).andExpect(status().isOk()));
        assertThat(MomentClient.ids(mine.get("current"))).doesNotContain(id);
        assertThat(MomentClient.ids(mine.get("kept"))).doesNotContain(id);
    }

    // ---- Keep ----

    @Test
    void keep_atMostSix_chosenWhileLive_shownAfterTheDayIsOver_neverBackOnToday() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 7; i++) ids.add(moments.say(asha, "COMMUNITY", "Worth keeping " + i));
        for (int i = 0; i < 6; i++) {
            moments.keep(asha, ids.get(i)).andExpect(status().isOk()).andExpect(jsonPath("$.kept").value(true));
        }
        moments.keep(asha, ids.get(0)).andExpect(status().isOk());              // again: nothing changes
        moments.keep(asha, ids.get(6)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You can keep 6 moments. Un-keep one first."));
        moments.unkeep(asha, ids.get(5)).andExpect(status().isNoContent());
        moments.keep(asha, ids.get(6)).andExpect(status().isOk());

        JsonNode mine = moments.json(moments.mine(asha).andExpect(status().isOk()));
        assertThat(mine.get("keptMax").asInt()).isEqualTo(6);
        assertThat(MomentClient.ids(mine.get("kept"))).containsExactlyInAnyOrder(ids.get(0), ids.get(1), ids.get(2),
                ids.get(3), ids.get(4), ids.get(6));
        assertThat(MomentClient.ids(mine.get("current"))).containsAll(ids);

        // Only the author keeps; for anyone else the request is about something that is not theirs.
        moments.keep(bala, ids.get(5)).andExpect(status().isNotFound());
        moments.unkeep(bala, ids.get(0)).andExpect(status().isNotFound());

        clock.advance(Duration.ofHours(25));
        // Kept: still readable (on the profile), marked ended, with a rough time; un-kept: gone.
        JsonNode kept = moments.json(moments.get_(chitra, ids.get(0)).andExpect(status().isOk()));
        assertThat(kept.get("status").asText()).isEqualTo("ENDED");
        assertThat(kept.get("kept").asBoolean()).isTrue();
        assertThat(kept.get("when").get("day").asText()).isIn("YESTERDAY", "THIS_WEEK");
        assertThat(kept.get("visibleAt").isNull()).isTrue();
        moments.get_(chitra, ids.get(5)).andExpect(status().isNotFound());
        // N-48: never back on Today.
        assertThat(moments.wholeToday(chitra, "ALL")).doesNotContainAnyElementsOf(ids);
        assertThat(moments.wholeToday(asha, "ALL")).doesNotContainAnyElementsOf(ids);
        // Chosen while live: an ended moment cannot be kept any more.
        moments.keep(asha, ids.get(5)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a moment that is live on Today can be kept"));

        clock.advance(Duration.ofDays(10));
        moments.get_(chitra, ids.get(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.when.day").value("THIS_MONTH"))
                .andExpect(jsonPath("$.when.part").doesNotExist());
        // Un-keep at any time: then it is gone for everyone else.
        moments.unkeep(asha, ids.get(0)).andExpect(status().isNoContent());
        moments.get_(chitra, ids.get(0)).andExpect(status().isNotFound());
    }

    // ---- Delete (there is no edit: N-54) ----

    @Test
    void onlyTheAuthorDeletes_andThereIsNoEdit() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Delete me");
        moments.delete_(bala, id).andExpect(status().isNotFound());
        moments.get_(bala, id).andExpect(status().isOk());
        social.perform(asha, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/moments/{id}", id).contentType("application/json").content("{\"text\":\"Edited\"}"))
                .andExpect(status().isMethodNotAllowed());
        moments.delete_(asha, id).andExpect(status().isNoContent());
        moments.get_(asha, id).andExpect(status().isNotFound());
        moments.get_(bala, id).andExpect(status().isNotFound());
        assertThat(moments.wholeToday(bala, "MINE")).doesNotContain(id);
        moments.get_(asha, "not-an-id").andExpect(status().isNotFound());
    }

    // ---- A moment that waits for a human (the rules that hold one arrive in B2 and B3; its life is decided here) ----

    @Test
    void aMomentThatWaits_isSeenByItsAuthorOnly_andIts24HoursStartWhenItIsReleased() throws Exception {
        long miet = jdbc.queryForObject("SELECT id FROM communities WHERE code = 'MIET'", Long.class);
        LocalDateTime written = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Moment waiting = momentRows.saveAndFlush(new Moment(asha.id(), miet, Kind.SAY, Audience.COMMUNITY,
                "Waiting for a moderator", written));
        String id = waiting.getPublicId();

        // No shadow state: the author sees it, marked as waiting; nobody else can, and it is not on any wall.
        JsonNode own = moments.json(moments.get_(asha, id).andExpect(status().isOk()));
        assertThat(own.get("status").asText()).isEqualTo("WAITING");
        assertThat(own.get("when").isNull()).isTrue();
        assertThat(own.get("visibleAt").isNull()).isTrue();
        assertThat(own.get("expiresAt").isNull()).isTrue();
        assertThat(MomentClient.ids(moments.json(moments.mine(asha)).get("current"))).contains(id);
        moments.get_(bala, id).andExpect(status().isNotFound());
        assertThat(moments.wholeToday(bala, "ALL")).doesNotContain(id);
        assertThat(moments.wholeToday(asha, "ALL")).doesNotContain(id);
        moments.keep(asha, id).andExpect(status().isConflict());

        // It waits two days: nothing runs out meanwhile.
        clock.advance(Duration.ofDays(2));
        moments.get_(asha, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING"));

        // Released: live for a full 24 hours from now, at the top of Today.
        LocalDateTime released = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Moment row = momentRows.findByPublicId(id).orElseThrow();
        row.publish(released, MomentPolicy.LIFE);
        momentRows.saveAndFlush(row);
        assertThat(moments.todayIds(bala, "MINE")).startsWith(id);
        JsonNode live = moments.json(moments.get_(asha, id).andExpect(status().isOk()));
        assertThat(live.get("status").asText()).isEqualTo("LIVE");
        Instant visibleAt = Instant.parse(live.get("visibleAt").asText());
        assertThat(Duration.between(Instant.parse(live.get("createdAt").asText()), visibleAt))
                .isGreaterThanOrEqualTo(Duration.ofDays(2));
        assertThat(Instant.parse(live.get("expiresAt").asText())).isEqualTo(visibleAt.plus(Duration.ofHours(24)));
        // Others see when it reached them, not when it was written.
        moments.get_(bala, id).andExpect(status().isOk()).andExpect(jsonPath("$.when.day").value("TODAY"));
        clock.advance(Duration.ofHours(23));
        moments.get_(bala, id).andExpect(status().isOk());
    }

    @Test
    void aHeldOrRemovedMoment_staysWithItsAuthor_aCollapsedOneStaysOnToday() throws Exception {
        String held = moments.say(asha, "COMMUNITY", "Held by a moderator");
        String collapsed = moments.say(asha, "COMMUNITY", "Collapsed");
        Moment row = momentRows.findByPublicId(held).orElseThrow();
        row.moderate(ModerationState.HELD, ModerationReason.NEEDS_REVIEW, UUID.randomUUID().toString());
        momentRows.saveAndFlush(row);
        Moment other = momentRows.findByPublicId(collapsed).orElseThrow();
        other.moderate(ModerationState.COLLAPSED, null, null);
        momentRows.saveAndFlush(other);

        moments.get_(bala, held).andExpect(status().isNotFound());
        assertThat(moments.wholeToday(bala, "MINE")).contains(collapsed).doesNotContain(held);
        moments.get_(asha, held).andExpect(status().isOk());
        assertThat(moments.wholeToday(asha, "ALL")).contains(collapsed).doesNotContain(held);
        moments.keep(asha, held).andExpect(status().isConflict());

        row = momentRows.findByPublicId(held).orElseThrow();
        row.moderate(ModerationState.REMOVED, ModerationReason.HARASSMENT, UUID.randomUUID().toString());
        momentRows.saveAndFlush(row);
        moments.get_(bala, held).andExpect(status().isNotFound());
        moments.get_(asha, held).andExpect(status().isOk());
    }
}
