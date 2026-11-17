package com.safesphere.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.moderation.service.ContentModerationService;
import com.safesphere.backend.social.service.SocialMediaCleanupJob;
import com.safesphere.backend.support.MomentClient;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.SocialClient;
import com.safesphere.backend.support.SocialClient.Person;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
 * Phase N4-B3: moderation of moments through the real API: reports (nothing the reported person can observe, the
 * collapse at three independent counting reports, a new member's report counting only after their first week), "This
 * is about me" (hidden at once, a priority case, a human decides, the author never learns who asked), the moderator's
 * case for a new member's held photo (released only by a person; its 24 hours start then), decisions with a reason,
 * strikes, a pause, an appeal decided by another moderator, and who may not decide.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class MomentModerationApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContentModerationService moderation;
    @Autowired private SocialMediaCleanupJob cleanup;

    private SocialClient social;
    private MomentClient moments;
    private Person asha;      // the author (established)
    private Person bala;      // connected to Asha (established)
    private Person chitra;    // a member, not connected (established)
    private Person mod;
    private Person mod2;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        social = new SocialClient(mockMvc, objectMapper, users);
        moments = new MomentClient(mockMvc, objectMapper);
        asha = social.person("Asha");
        bala = social.person("Bala");
        chitra = social.person("Chitra");
        mod = social.person("Modone");
        mod2 = social.person("Modtwo");
        social.connect(asha, bala);
        social.makeModerator(mod);
        social.makeModerator(mod2);
        for (Person p : List.of(asha, bala, chitra, mod, mod2)) establish(p);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** As if the membership had been approved 8 days ago: past the 7-day new-member period. */
    private void establish(Person who) {
        LocalDateTime then = now().minusDays(8);
        jdbc.update("UPDATE memberships SET verified_at = ?, updated_at = ? WHERE user_id = ?", then, then, who.id());
    }

    private String caseOf(String momentId) {
        return jdbc.queryForObject("SELECT c.public_id FROM content_cases c JOIN moments m ON m.id = c.moment_id"
                + " WHERE m.public_id = ? AND c.status = 'OPEN'", String.class, momentId);
    }

    private int strikes(Person p) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM social_strikes WHERE user_id = ?", Integer.class, p.id());
    }

    private List<String> queue(Person moderator) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode c : social.json(social.contentCases(moderator, "OPEN").andExpect(status().isOk()))) {
            ids.add(c.get("caseId").asText());
        }
        return ids;
    }

    // ---- Reports ----

    @Test
    void aReport_changesNothingTheAuthorCanSee_andIsTheSameAnswerTwice() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "A plain moment");
        social.report(chitra, "MOMENT", id, "SPAM", null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        social.report(chitra, "MOMENT", id, "HATE", null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports r JOIN moments m ON m.id = r.moment_id"
                + " WHERE m.public_id = ?", Integer.class, id)).isEqualTo(1);

        JsonNode own = moments.json(moments.get_(asha, id).andExpect(status().isOk()));
        assertThat(own.get("notice").isNull()).isTrue();
        assertThat(own.get("status").asText()).isEqualTo("LIVE");
        assertThat(social.json(social.standing(asha)).get("decisions")).isEmpty();
        moments.get_(bala, id).andExpect(status().isOk()).andExpect(jsonPath("$.collapsed").value(false));

        // The reporter sees their own report and nothing about anyone else's.
        JsonNode mine = social.json(social.myReports(chitra).andExpect(status().isOk())).get(0);
        assertThat(mine.get("targetKind").asText()).isEqualTo("MOMENT");
        assertThat(mine.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(mine.get("period").asText()).isEqualTo("THIS_WEEK");
    }

    @Test
    void whatCannotBeReported() throws Exception {
        String id = moments.say(asha, "MY_PEOPLE", "For my people");
        social.report(asha, "MOMENT", id, "SPAM", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can't report your own moments or profile"));
        social.report(chitra, "MOMENT", id, "SPAM", null).andExpect(status().isNotFound());       // not her audience
        social.report(bala, "MOMENT", UUID.randomUUID().toString(), "SPAM", null).andExpect(status().isNotFound());
        social.report(bala, "MOMENT", id, "IMPERSONATION", null).andExpect(status().isBadRequest());
        social.report(bala, "MOMENT", id, "OTHER", null).andExpect(status().isBadRequest());
        social.report(bala, "MOMENT", id, "SPAM", "a note").andExpect(status().isBadRequest());
        // The codes only a rule or a moderator uses are not reasons a person can give.
        for (String reason : List.of("NEEDS_REVIEW", "AWAITING_CHECK", "NEW_MEMBER_PHOTO", "ABOUT_ME")) {
            social.report(bala, "MOMENT", id, reason, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Choose a reason"));
        }
        // The kinds that left with the old social surface.
        for (String kind : List.of("POST", "COMMENT", "PLACE")) {
            social.report(bala, kind, id, "SPAM", null).andExpect(status().isBadRequest());
        }
        social.report(bala, "MOMENT", id, "OTHER", "It names my hostel room").andExpect(status().isAccepted());
        social.report(bala, "PERSON", chitra.publicId(), "IMPERSONATION", null).andExpect(status().isAccepted());
    }

    @Test
    void threeIndependentCountingReports_collapse_aNewMembersReportCountsOnlyAfterTheirFirstWeek() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Reported by three");
        Person fresh = social.person("Fresh"); // verified a moment ago: a new member
        social.report(bala, "MOMENT", id, "HARASSMENT", null).andExpect(status().isAccepted());
        social.report(chitra, "MOMENT", id, "HARASSMENT", null).andExpect(status().isAccepted());
        social.report(fresh, "MOMENT", id, "HARASSMENT", null).andExpect(status().isAccepted());

        // Three reports, but only two count: nothing is collapsed. The moderators see all three.
        moments.get_(chitra, id).andExpect(jsonPath("$.collapsed").value(false));
        JsonNode summary = social.json(social.contentCase(mod, caseOf(id)).andExpect(status().isOk())).get("summary");
        assertThat(summary.get("reasons").get("HARASSMENT").asInt()).isEqualTo(3);
        assertThat(summary.get("counting").asInt()).isEqualTo(2);

        // A fourth, established reporter makes three counting ones: collapsed at once.
        social.report(mod2, "MOMENT", id, "HARASSMENT", null).andExpect(status().isAccepted());
        moments.get_(chitra, id).andExpect(status().isOk()).andExpect(jsonPath("$.collapsed").value(true));
        assertThat(moments.todayIds(chitra, null)).contains(id); // collapsed stays on the wall, behind a tap
        // The author is told it is under review: no reason, nobody named, no word about reports.
        JsonNode notice = moments.json(moments.get_(asha, id)).get("notice");
        assertThat(notice.get("state").asText()).isEqualTo("COLLAPSED");
        assertThat(notice.get("reason").isNull()).isTrue();
        assertThat(notice.get("by").isNull()).isTrue();
        assertThat(notice.get("reviewable").asBoolean()).isFalse();
    }

    @Test
    void aNewMembersReport_startsToCount_whenTheirSevenDaysAreOver() throws Exception {
        Person f1 = social.person("Freshone");
        Person f2 = social.person("Freshtwo");
        Person f3 = social.person("Freshthree");
        String id = moments.say(asha, "COMMUNITY", "Reported by three new members");
        for (Person f : List.of(f1, f2, f3)) {
            social.report(f, "MOMENT", id, "SPAM", null).andExpect(status().isAccepted());
        }
        moments.get_(chitra, id).andExpect(jsonPath("$.collapsed").value(false));
        // Keep the moment readable after its 24 hours, so the collapse can be seen a week later.
        moments.keep(asha, id).andExpect(status().isOk());

        clock.advance(Duration.ofDays(7).minusMinutes(5));
        moderation.maintain();
        moments.get_(chitra, id).andExpect(jsonPath("$.collapsed").value(false));
        clock.advance(Duration.ofMinutes(10));
        moderation.maintain();
        moments.get_(chitra, id).andExpect(jsonPath("$.collapsed").value(true));
    }

    // ---- "This is about me" (N-52) ----

    @Test
    void aboutMe_hidesAtOnce_opensAPriorityCase_andTheAuthorNeverLearnsWhoAsked() throws Exception {
        String other = moments.say(bala, "COMMUNITY", "An ordinary reported moment");
        social.report(chitra, "MOMENT", other, "THREAT", null).andExpect(status().isAccepted());

        String id = moments.say(asha, "COMMUNITY", "Chitra failed her arrears again");
        Instant expires = Instant.parse(moments.json(moments.get_(asha, id)).get("expiresAt").asText());
        social.aboutMe(chitra, id).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RECEIVED"));

        // Gone for everyone but the author, at once.
        for (Person other2 : List.of(chitra, bala, mod)) moments.get_(other2, id).andExpect(status().isNotFound());
        assertThat(moments.todayIds(bala, null)).doesNotContain(id);
        JsonNode own = moments.json(moments.get_(asha, id).andExpect(status().isOk()));
        assertThat(own.get("notice").get("state").asText()).isEqualTo("HELD");
        assertThat(own.get("notice").get("reason").asText()).isEqualTo("NEEDS_REVIEW"); // never "ABOUT_ME"
        assertThat(own.get("notice").get("by").asText()).isEqualTo("SYSTEM");
        assertThat(own.toString()).doesNotContain("ABOUT_ME").doesNotContain(chitra.handle())
                .doesNotContain(chitra.publicId());
        assertThat(social.json(social.standing(asha)).toString()).doesNotContain("ABOUT_ME");
        // N-60: a hold does not move the end the moment already had.
        assertThat(Instant.parse(own.get("expiresAt").asText())).isEqualTo(expires);

        // The same answer again; nothing new is stored.
        social.aboutMe(chitra, id).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_reports r JOIN moments m ON m.id = r.moment_id"
                + " WHERE m.public_id = ?", Integer.class, id)).isEqualTo(1);

        // First in the queue, before a reported threat; the moderator sees it is an about-me case.
        String caseId = caseOf(id);
        List<String> order = queue(mod);
        assertThat(order.indexOf(caseId)).isLessThan(order.indexOf(caseOf(other)));
        JsonNode detail = social.json(social.contentCase(mod, caseId).andExpect(status().isOk()));
        assertThat(detail.get("summary").get("openedBy").asText()).isEqualTo("ABOUT_ME");
        assertThat(detail.get("summary").get("contentState").asText()).isEqualTo("HELD");
        assertThat(detail.get("summary").get("waitingHours").asInt()).isZero();
        assertThat(detail.get("content").get("body").asText()).startsWith("Chitra failed");
        assertThat(detail.get("content").get("kind").asText()).isEqualTo("SAY");
        // Pseudonyms only: no handle, no name of the author or of the person who asked.
        assertThat(detail.toString()).doesNotContain(asha.handle()).doesNotContain(chitra.handle());

        // A human decides: remove, with a reason. The author sees the decision and can ask for a review.
        social.act(mod, caseId, "CONTENT_REMOVE", "NO_VIOLATION").andExpect(status().isBadRequest());
        social.act(mod, caseId, "CONTENT_REMOVE", "HARASSMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("CLOSED"));
        moments.get_(chitra, id).andExpect(status().isNotFound());
        JsonNode removed = moments.json(moments.get_(asha, id)).get("notice");
        assertThat(removed.get("state").asText()).isEqualTo("REMOVED");
        assertThat(removed.get("reason").asText()).isEqualTo("HARASSMENT");
        assertThat(removed.get("by").asText()).isEqualTo("MODERATOR");
        assertThat(removed.get("decisionId").asText()).isNotBlank();
        assertThat(strikes(asha)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT in_review FROM moments WHERE public_id = ?", Boolean.class, id)).isFalse();
    }

    @Test
    void aboutMe_keptByAModerator_isBackForEveryone_andWhoMayNotAsk() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Nothing about anyone");
        social.aboutMe(asha, id).andExpect(status().isBadRequest());                              // one's own
        String hidden = moments.say(asha, "MY_PEOPLE", "For my people only");
        social.aboutMe(chitra, hidden).andExpect(status().isNotFound());                          // cannot see it

        social.aboutMe(bala, id).andExpect(status().isAccepted());
        moments.get_(chitra, id).andExpect(status().isNotFound());
        String caseId = caseOf(id);
        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        moments.get_(chitra, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));
        assertThat(moments.json(moments.get_(asha, id)).get("notice").isNull()).isTrue();
        assertThat(social.json(social.standing(asha)).get("decisions")).isEmpty(); // a keep is never shown

        // The same person asking again changes nothing (their request was answered by a human).
        social.aboutMe(bala, id).andExpect(status().isAccepted());
        moments.get_(chitra, id).andExpect(status().isOk());
    }

    @Test
    void theAuthor_theRequester_andAReporter_neverDecideTheirOwnCase() throws Exception {
        String id = moments.say(mod, "COMMUNITY", "Written by a moderator");
        social.aboutMe(mod2, id).andExpect(status().isAccepted());
        String caseId = caseOf(id);
        Person mod3 = social.person("Modthree");
        social.makeModerator(mod3);

        for (Person party : List.of(mod, mod2)) {
            social.contentCase(party, caseId).andExpect(status().isForbidden());
            social.act(party, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isForbidden());
            social.caseMedia(party, caseId).andExpect(status().isForbidden());
        }
        JsonNode listed = null;
        for (JsonNode c : social.json(social.contentCases(mod2, "OPEN"))) {
            if (c.get("caseId").asText().equals(caseId)) listed = c;
        }
        assertThat(listed.get("youTookPart").asBoolean()).isTrue();
        social.contentCases(chitra, "OPEN").andExpect(status().isForbidden());                    // not a moderator
        social.act(mod3, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
    }

    // ---- The new-member photo hold (N-56, N-57) ----

    @Test
    void aNewMembersPhoto_isReleasedOnlyByAPerson_andIts24HoursStartThen() throws Exception {
        Person fresh = social.person("Fresh");
        String id = moments.json(moments.create(fresh, MomentClient.withPhoto("SAY", "COMMUNITY", "First photo",
                moments.uploadPhoto(fresh, TestImages.jpeg(120, 80)))).andExpect(status().isCreated())).get("id").asText();
        JsonNode waiting = moments.json(moments.get_(fresh, id));
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING");
        assertThat(waiting.get("notice").get("reason").asText()).isEqualTo("NEW_MEMBER_PHOTO");
        assertThat(waiting.get("notice").get("by").asText()).isEqualTo("SYSTEM");

        // The case exists without any text service, and the moderator can look at the photo.
        String caseId = caseOf(id);
        JsonNode detail = social.json(social.contentCase(mod, caseId).andExpect(status().isOk()));
        assertThat(detail.get("summary").get("openedBy").asText()).isEqualTo("SYSTEM");
        assertThat(detail.get("summary").get("topReason").asText()).isEqualTo("NEW_MEMBER_PHOTO");
        assertThat(detail.get("content").get("photo").get("width").asInt()).isEqualTo(120);
        assertThat(detail.get("author").get("newMemberUntil").isNull()).isFalse();
        assertThat(detail.get("author").get("suggestedStep").asText()).isEqualTo("WARN");
        social.caseMedia(mod, caseId).andExpect(status().isOk());
        social.caseMedia(chitra, caseId).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT in_review FROM moments WHERE public_id = ?", Boolean.class, id)).isTrue();

        // Three hours later a moderator keeps it: live from NOW for 24 hours, not from when it was shared.
        clock.advance(Duration.ofHours(3));
        Instant released = clock.instant().truncatedTo(ChronoUnit.MICROS);
        social.act(mod, caseId, "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        JsonNode live = moments.json(moments.get_(fresh, id));
        assertThat(live.get("status").asText()).isEqualTo("LIVE");
        assertThat(live.get("notice").isNull()).isTrue();
        Instant visibleAt = Instant.parse(live.get("visibleAt").asText()); // the test clock runs on from "released"
        assertThat(visibleAt).isBetween(released, released.plusSeconds(30));
        assertThat(Instant.parse(live.get("expiresAt").asText())).isEqualTo(visibleAt.plus(Duration.ofHours(24)));
        moments.get_(chitra, id).andExpect(status().isOk());
        moments.photo(chitra, id).andExpect(status().isOk());
        assertThat(moments.todayIds(chitra, null)).contains(id);
        clock.advance(Duration.ofHours(23));
        moments.get_(chitra, id).andExpect(status().isOk()); // 26 hours after sharing, 23 after release: still live
    }

    @Test
    void aRemovedPhoto_canBeAppealed_andAnotherModeratorOverturnsIt() throws Exception {
        Person fresh = social.person("Fresh");
        String id = moments.json(moments.create(fresh, MomentClient.withPhoto("SAY", "COMMUNITY", null,
                moments.uploadPhoto(fresh, TestImages.jpeg(64, 64)))).andExpect(status().isCreated())).get("id").asText();
        String caseId = caseOf(id);
        social.act(mod, caseId, "CONTENT_REMOVE", "NUDITY_OR_VIOLENCE").andExpect(status().isOk());
        assertThat(strikes(fresh)).isEqualTo(1);
        String decisionId = moments.json(moments.get_(fresh, id)).get("notice").get("decisionId").asText();

        JsonNode standing = social.json(social.standing(fresh).andExpect(status().isOk()));
        JsonNode decision = standing.get("decisions").get(0);
        assertThat(decision.get("decisionId").asText()).isEqualTo(decisionId);
        assertThat(decision.get("targetKind").asText()).isEqualTo("MOMENT");
        assertThat(decision.get("targetId").asText()).isEqualTo(id);
        assertThat(decision.get("canAppeal").asBoolean()).isTrue();

        social.appealDecision(fresh, decisionId).andExpect(status().isAccepted());
        social.appealDecision(fresh, decisionId).andExpect(status().isConflict());
        // The moderator who decided does not see or decide the appeal; another one does.
        JsonNode appeals = social.json(social.contentAppeals(mod2).andExpect(status().isOk()));
        String appealId = null;
        for (JsonNode a : appeals) if (a.get("caseId").asText().equals(caseId)) appealId = a.get("appealId").asText();
        social.decideContentAppeal(mod, appealId, "OVERTURNED").andExpect(status().isForbidden());
        social.decideContentAppeal(mod2, appealId, "OVERTURNED").andExpect(status().isOk());

        // Back, published for the first time now, and the strike is gone.
        assertThat(strikes(fresh)).isZero();
        moments.get_(chitra, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));
        assertThat(moments.json(moments.get_(fresh, id)).get("notice").isNull()).isTrue();
    }

    // ---- Decisions about content and people ----

    @Test
    void hold_remove_oneStrikePerCase_andAPauseStopsSharingOnly() throws Exception {
        String id = moments.say(asha, "COMMUNITY", "Something a moderator will remove");
        social.report(chitra, "MOMENT", id, "THREAT", null).andExpect(status().isAccepted());
        String caseId = caseOf(id);

        social.act(mod, caseId, "CONTENT_HOLD", "NEEDS_REVIEW").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("OPEN"))
                .andExpect(jsonPath("$.summary.contentState").value("HELD"));
        moments.get_(bala, id).andExpect(status().isNotFound());
        moments.get_(asha, id).andExpect(jsonPath("$.notice.state").value("HELD"))
                .andExpect(jsonPath("$.notice.by").value("MODERATOR"));

        social.act(mod, caseId, "CONTENT_REMOVE", "THREAT").andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.outcome").value("ACTION_TAKEN"));
        assertThat(strikes(asha)).isEqualTo(1);
        // A person step on the same confirmed case: no second strike.
        social.act(mod, caseId, "PERSON_PAUSE_POSTING", "THREAT").andExpect(status().isOk());
        assertThat(strikes(asha)).isEqualTo(1);

        // The pause stops sharing and photos, and nothing else.
        moments.create(asha, MomentClient.body("SAY", "COMMUNITY", "While paused")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("A moderator has paused your sharing for now. "
                        + "You shows until when, and you can ask for a review there."));
        moments.upload(asha, TestImages.jpeg(16, 16), "image/jpeg").andExpect(status().isForbidden());
        moments.today(asha, null, null).andExpect(status().isOk());
        moments.delete_(asha, id).andExpect(status().isNoContent());
        JsonNode standing = social.json(social.standing(asha).andExpect(status().isOk()));
        assertThat(standing.get("restriction").get("level").asText()).isEqualTo("POSTING_PAUSED");

        JsonNode mine = social.json(social.myReports(chitra)).get(0);
        assertThat(mine.get("status").asText()).isEqualTo("REVIEWED");
        assertThat(mine.get("outcome").asText()).isEqualTo("ACTION_TAKEN");
    }

    @Test
    void aDeletedMoment_takesItsCaseOutOfTheQueue_andAnOpenCaseKeepsAMomentFromThePurge() throws Exception {
        String deleted = moments.say(asha, "COMMUNITY", "Deleted while reported");
        social.report(chitra, "MOMENT", deleted, "SPAM", null).andExpect(status().isAccepted());
        String deletedCase = caseOf(deleted);
        moments.delete_(asha, deleted).andExpect(status().isNoContent()); // never refused: a refusal would tell
        assertThat(queue(mod)).doesNotContain(deletedCase);
        social.contentCase(mod, deletedCase).andExpect(status().isNotFound());
        moderation.maintain();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_cases WHERE public_id = ?", Integer.class,
                deletedCase)).isZero();

        // N-47: 8 days after its end a moment is purged, unless an open case still needs it.
        String waiting = moments.say(asha, "COMMUNITY", "Reported and never decided");
        String plain = moments.say(asha, "COMMUNITY", "Nobody reported this");
        social.report(chitra, "MOMENT", waiting, "SPAM", null).andExpect(status().isAccepted());
        clock.advance(Duration.ofDays(9));
        cleanup.run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moments WHERE public_id = ?", Integer.class, plain))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moments WHERE public_id = ?", Integer.class, waiting))
                .isEqualTo(1);
        // Decided: the next run may take it.
        social.act(mod, caseOf(waiting), "CONTENT_KEEP", "NO_VIOLATION").andExpect(status().isOk());
        cleanup.run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM moments WHERE public_id = ?", Integer.class, waiting))
                .isZero();
    }

    @Test
    void aPersonCase_showsTheProfileEveryMemberSees() throws Exception {
        social.report(chitra, "PERSON", asha.publicId(), "IMPERSONATION", null).andExpect(status().isAccepted());
        String caseId = jdbc.queryForObject("SELECT public_id FROM content_cases WHERE person_id = ? AND status = 'OPEN'",
                String.class, asha.id());
        JsonNode person = social.json(social.contentCase(mod, caseId).andExpect(status().isOk())).get("content")
                .get("person");
        assertThat(person.get("handle").asText()).isEqualTo(asha.handle());
        assertThat(person.get("programme").asText()).isEqualTo("BTECH_IT");
        assertThat(person.has("city")).isFalse();
        assertThat(person.has("interests")).isFalse();
        social.act(mod, caseId, "PERSON_KEEP", "NO_VIOLATION").andExpect(status().isOk());
    }

    // ---- Profile moments (N-51) ----

    @Test
    void aProfile_showsTheLiveAndKeptMomentsTheViewerMaySee() throws Exception {
        String forAll = moments.say(asha, "COMMUNITY", "For everyone");
        String forPeople = moments.say(asha, "MY_PEOPLE", "For my people");
        moments.keep(asha, forAll).andExpect(status().isOk());

        JsonNode asConnection = moments.json(mockMvcGet(bala, asha));
        assertThat(MomentClient.ids(asConnection.get("live"))).contains(forAll, forPeople);
        assertThat(MomentClient.ids(asConnection.get("kept"))).containsExactly(forAll);
        JsonNode asStranger = moments.json(mockMvcGet(chitra, asha));
        assertThat(MomentClient.ids(asStranger.get("live"))).contains(forAll).doesNotContain(forPeople);

        // After the day is over only the kept one remains, and never an exact time for others.
        clock.advance(Duration.ofHours(25));
        JsonNode later = moments.json(mockMvcGet(chitra, asha));
        assertThat(later.get("live")).isEmpty();
        assertThat(MomentClient.ids(later.get("kept"))).containsExactly(forAll);
        assertThat(later.get("kept").get(0).get("createdAt").isNull()).isTrue();

        social.block(asha, chitra).andExpect(status().isNoContent());
        mockMvcGet(chitra, asha).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions mockMvcGet(Person viewer, Person owner) throws Exception {
        return social.perform(viewer,
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/profiles/{id}/moments",
                        owner.publicId()));
    }
}
