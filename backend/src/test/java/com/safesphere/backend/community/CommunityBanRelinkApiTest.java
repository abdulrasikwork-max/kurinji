package com.safesphere.backend.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.account.service.AccountEraser;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.repository.BanRepository;
import com.safesphere.backend.community.repository.CommunityActionRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.NumberRelinkRepository;
import com.safesphere.backend.community.repository.VerificationRequestRepository;
import com.safesphere.backend.community.service.CommunityRules;
import com.safesphere.backend.community.service.CommunityUpkeepJob;
import com.safesphere.backend.community.service.IdPhotoStore;
import com.safesphere.backend.support.CapturingOtpSenderConfig;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.support.TestImages;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Phase N3 (unit B3) through the real API: bans (on a roll number and / or a phone number) remove community access
 * ONLY: sign-in, the token, SOS, Safe Walk and the Safety Circle keep working; a ban survives account deletion and is
 * lifted by an admin; the admin's end of a membership; the hourly upkeep (graduation, expiry, stray photo files); moving
 * a member's account to a new number (admin + in person, refused while an SOS runs).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({MutableClockConfig.class, CapturingOtpSenderConfig.class})
class CommunityBanRelinkApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private VerificationRequestRepository requests;
    @Autowired private NumberRelinkRepository relinks;
    @Autowired private BanRepository bans;
    @Autowired private CommunityActionRepository actions;
    @Autowired private CommunityUpkeepJob upkeep;
    @Autowired private IdPhotoStore photos;
    @Autowired private AccountEraser eraser;

    private CommunityClient community;

    @BeforeEach
    void setUp() {
        clock.reset();
        community = new CommunityClient(mockMvc, objectMapper);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private int year() {
        return CommunityRules.firstYear(now()) + 1;
    }

    private Account admin(String name) throws Exception {
        Account a = community.unverified(name);
        User user = users.findById(a.id()).orElseThrow();
        user.setRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        return a;
    }

    /** An account verified in person by [admin] with [roll]. */
    private Account verified(Account admin, String name, String roll) throws Exception {
        Account p = community.unverified(name);
        String code = community.json(community.start(p, "IN_PERSON", roll, "BE_EEE", year()))
                .get("request").get("code").asText();
        String ref = community.json(community.lookup(admin, code)).get("ref").asText();
        community.decideInPerson(admin, ref, Map.of("approve", true, "rollNumber", roll))
                .andExpect(jsonPath("$.outcome").value("VERIFIED"));
        return p;
    }

    private ResultActions ban(Account admin, String phone, String roll, String reason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (phone != null) body.put("phone", phone);
        if (roll != null) body.put("rollNumber", roll);
        body.put("reason", reason);
        return community.perform(admin, post("/api/admin/community/bans"), body);
    }

    /** SOS, Safe Walk, a Safety Circle contact and the account itself: every one must keep working. */
    private void safetyStillWorks(String token) throws Exception {
        Account a = new Account(token, 0, "", "");
        community.perform(a, get("/api/users/me"), null).andExpect(status().isOk());
        long sos = community.json(community.perform(a, post("/api/emergencies"), Map.of("triggerType", "MANUAL"))
                .andExpect(status().isCreated())).get("id").asLong();
        community.perform(a, put("/api/emergencies/" + sos + "/cancel"), null).andExpect(status().isOk());
        Map<String, Object> walk = new LinkedHashMap<>();
        walk.put("destinationLabel", null);
        walk.put("checkInIntervalMinutes", 10);
        long journey = community.json(community.perform(a, post("/api/journeys"), walk)
                .andExpect(status().isCreated())).get("id").asLong();
        community.perform(a, put("/api/journeys/" + journey + "/cancel"), null).andExpect(status().isOk());
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("name", "Amma");
        contact.put("phoneNumber", "98765" + String.format("%05d", Math.floorMod(UUID.randomUUID().hashCode(), 100000)));
        contact.put("relationship", "FAMILY");
        contact.put("priority", 1);
        community.perform(a, post("/api/safety-contacts"), contact).andExpect(status().isCreated());
    }

    // ---- bans -----------------------------------------------------------------------------------------------------

    @Test
    void aBanOnTheRollNumber_endsTheMembership_butSignInAndEverySafetyFeatureKeepWorking() throws Exception {
        Account admin = admin("Root Admin");
        Account p = verified(admin, "Asha Devi", "E1237001");
        User before = users.findById(p.id()).orElseThrow();

        JsonNode view = community.json(ban(admin, null, "e123-7001", "HARASSMENT").andExpect(status().isOk()));
        assertThat(view.get("onRollNumber").asBoolean()).isTrue();
        assertThat(view.get("onPhone").asBoolean()).isFalse();
        assertThat(view.get("person").asText()).isEqualTo("Asha Devi");
        assertThat(view.toString()).doesNotContain("7001");

        community.me(p).andExpect(jsonPath("$.state").value("BANNED"))
                .andExpect(jsonPath("$.outcome.reason").value("HARASSMENT"));
        community.perform(p, get("/api/connections"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_NEEDED"));
        Membership m = memberships.findByUserId(p.id()).orElseThrow();
        assertThat(m.getEndReason().name()).isEqualTo("BANNED");

        // The account is untouched: same token, same stamp, still enabled; a new sign-in works; safety works.
        User after = users.findById(p.id()).orElseThrow();
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.getCredentialsChangedAt()).isEqualTo(before.getCredentialsChangedAt());
        safetyStillWorks(p.token());
        JsonNode session = TestAccounts.signIn(mockMvc, objectMapper, p.key(), null);
        safetyStillWorks(session.get("accessToken").asText());

        // The same roll number cannot be verified again, even from another account.
        Account again = community.unverified("Asha Again");
        String code = community.json(community.start(again, "IN_PERSON", "E1237001", "BE_EEE", year()))
                .get("request").get("code").asText();
        community.decideInPerson(admin, community.json(community.lookup(admin, code)).get("ref").asText(),
                Map.of("approve", true, "rollNumber", "E1237001")).andExpect(jsonPath("$.reason").value("BANNED"));
    }

    @Test
    void aBanOnThePhone_refusesAsking_deletesAnOpenRequestAndItsPhoto_andSafetyKeepsWorking() throws Exception {
        Account admin = admin("Root Admin");
        Account p = community.unverified("Ravi Kumar");
        community.start(p, "ID_PHOTO", "E1237002", "BE_EEE", year()).andExpect(status().isOk());
        community.uploadPhoto(p, TestImages.jpeg(300, 200), MediaType.IMAGE_JPEG).andExpect(status().isOk());
        String key = requests.findByUserId(p.id()).orElseThrow().getPhotoKey();

        ban(admin, TestAccounts.phoneFor(p.key()), null, "THREATS").andExpect(status().isOk());
        assertThat(requests.findByUserId(p.id())).isEmpty();
        assertThat(Files.exists(photos.root().resolve(key + ".idp"))).isFalse();
        community.start(p, "IN_PERSON", "E1237002", "BE_EEE", year()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_BANNED"));
        community.me(p).andExpect(jsonPath("$.state").value("BANNED"));
        safetyStillWorks(p.token());

        ban(admin, null, null, "THREATS").andExpect(status().isBadRequest());
        ban(admin, "123", null, "THREATS").andExpect(status().isBadRequest());
        ban(admin, TestAccounts.phoneFor(p.key()), null, "BAD_VIBES").andExpect(status().isBadRequest());
        ban(admin, TestAccounts.phoneFor(admin.key()), null, "OTHER").andExpect(status().isBadRequest());
        ban(p, null, "E1237009", "OTHER").andExpect(status().isForbidden());
    }

    @Test
    void liftingABan_letsThePersonBeVerifiedAgain() throws Exception {
        Account admin = admin("Root Admin");
        Account p = verified(admin, "Kavya Raj", "E1237003");
        long id = community.json(ban(admin, null, "E1237003", "OTHER")).get("id").asLong();
        community.json(community.perform(admin, post("/api/admin/community/bans/" + id + "/lift"), null)
                .andExpect(status().isOk()));
        community.me(p).andExpect(jsonPath("$.state").value("ENDED"))
                .andExpect(jsonPath("$.outcome.reason").value("BANNED"));
        verifiedAgain(admin, p, "E1237003");
        community.me(p).andExpect(jsonPath("$.state").value("MEMBER"));
        JsonNode list = community.json(community.perform(admin, get("/api/admin/community/bans"), null)
                .andExpect(status().isOk()));
        assertThat(list.toString()).contains("\"liftedAt\":\"");
    }

    private void verifiedAgain(Account admin, Account p, String roll) throws Exception {
        String code = community.json(community.start(p, "IN_PERSON", roll, "BE_EEE", year()))
                .get("request").get("code").asText();
        community.decideInPerson(admin, community.json(community.lookup(admin, code)).get("ref").asText(),
                Map.of("approve", true, "rollNumber", roll)).andExpect(jsonPath("$.outcome").value("VERIFIED"));
    }

    @Test
    void aBanSurvivesTheAccountsDeletion_withoutKeepingTheNumbers() throws Exception {
        Account admin = admin("Root Admin");
        Account p = verified(admin, "Selvi Mani", "E1237004");
        ban(admin, TestAccounts.phoneFor(p.key()), "E1237004", "HATE").andExpect(status().isOk());
        long bansBefore = bans.count();

        User user = users.findById(p.id()).orElseThrow();
        user.requestDeletion(now().minusDays(8), now().minusDays(1));
        users.saveAndFlush(user);
        assertThat(eraser.eraseIfDue(p.id())).isEqualTo(AccountEraser.Outcome.DELETED);
        assertThat(memberships.findByUserId(p.id())).isEmpty();
        assertThat(bans.count()).isEqualTo(bansBefore);

        // The same number signs up again: allowed (safety for everyone), but never verified.
        JsonNode fresh = TestAccounts.registerUnverified(mockMvc, objectMapper, "Selvi Mani", p.key());
        Account back = new Account(fresh.get("accessToken").asText(), fresh.get("user").get("id").asLong(), p.key(),
                "Selvi Mani");
        community.start(back, "IN_PERSON", "E1237099", "BE_EEE", year()).andExpect(status().isForbidden());
        safetyStillWorks(back.token());
    }

    @Test
    void anAdminEndsAMembership_shownAsRemoved_andLogged() throws Exception {
        Account admin = admin("Root Admin");
        Account p = verified(admin, "Meena Raj", "E1237005");
        community.perform(admin, post("/api/admin/community/members/end"),
                Map.of("phone", TestAccounts.e164For(p.key()))).andExpect(status().isNoContent());
        community.me(p).andExpect(jsonPath("$.state").value("ENDED"))
                .andExpect(jsonPath("$.outcome.reason").value("REMOVED"));
        community.perform(admin, post("/api/admin/community/members/end"),
                Map.of("phone", TestAccounts.e164For(p.key()))).andExpect(status().isConflict());
        assertThat(community.json(community.perform(admin, get("/api/admin/community/log"), null)).toString())
                .contains("\"action\":\"ENDED\"").contains("REMOVED");
        safetyStillWorks(p.token());
    }

    // ---- the upkeep job -------------------------------------------------------------------------------------------

    @Test
    void theUpkeepJob_marksGraduates_removesVerifiers_expiresRequests_andSweepsStrayFiles() throws Exception {
        Account admin = admin("Root Admin");
        Account graduate = verified(admin, "Final Year", "E1237006");
        community.perform(admin, put("/api/admin/community/verifiers"),
                Map.of("phone", TestAccounts.e164For(graduate.key()), "verifier", true))
                .andExpect(status().isNoContent());
        Account waiting = community.unverified("Waiting Person");
        community.start(waiting, "ID_PHOTO", "E1237007", "BE_EEE", year()).andExpect(status().isOk());
        community.uploadPhoto(waiting, TestImages.jpeg(300, 200), MediaType.IMAGE_JPEG).andExpect(status().isOk());
        String key = requests.findByUserId(waiting.id()).orElseThrow().getPhotoKey();
        Path stray = photos.root().resolve(UUID.randomUUID() + ".idp");
        Files.write(stray, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(stray, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        Membership m = memberships.findByUserId(graduate.id()).orElseThrow();
        clock.advance(Duration.between(clock.instant(), m.getEndsAt().toInstant(ZoneOffset.UTC)).plusMinutes(5));
        CommunityUpkeepJob.Outcome outcome = upkeep.run();
        assertThat(outcome.graduated()).isGreaterThanOrEqualTo(1);
        assertThat(outcome.requests()).isGreaterThanOrEqualTo(1);
        assertThat(outcome.files()).isGreaterThanOrEqualTo(1);

        Membership ended = memberships.findByUserId(graduate.id()).orElseThrow();
        assertThat(ended.getEndReason().name()).isEqualTo("GRADUATED");
        assertThat(ended.isVerifier()).isFalse();
        assertThat(ended.getEndedAt()).isEqualTo(ended.getEndsAt());
        community.me(graduate).andExpect(jsonPath("$.state").value("ENDED"))
                .andExpect(jsonPath("$.outcome.reason").value("GRADUATED"));
        assertThat(requests.findByUserId(waiting.id())).isEmpty();
        assertThat(Files.exists(photos.root().resolve(key + ".idp"))).isFalse();
        assertThat(Files.exists(stray)).isFalse();
        assertThat(upkeep.run().graduated()).as("each graduate once").isZero();
    }

    // ---- moving to a new number (N-25) ----------------------------------------------------------------------------

    @Test
    void movingToANewNumber_isAdminAndInPerson_endsEverySignIn_andTheNewNumberSignsIn() throws Exception {
        Account admin = admin("Root Admin");
        Account p = verified(admin, "Nila Check", "E1237008");
        String newKey = "community.new." + UUID.randomUUID() + "@example.com";
        String ticket = TestAccounts.seedTicket(mockMvc, TestAccounts.e164For(newKey));

        // Not signed in: the ticket is the credential (and is used up).
        JsonNode code = community.json(mockMvc.perform(post("/api/community/relink")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("ticket", ticket, "rollNumber", "E1237008"))))
                .andExpect(status().isOk()));
        assertThat(code.get("code").asText()).matches("^[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$");
        mockMvc.perform(post("/api/community/relink").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ticket", ticket, "rollNumber", "E1237008"))))
                .andExpect(status().is4xxClientError());

        Map<String, Object> wrongRoll = Map.of("code", code.get("code").asText(), "rollNumber", "E1237018");
        community.perform(admin, post("/api/admin/community/relinks"), wrongRoll).andExpect(status().isConflict());
        community.perform(p, post("/api/admin/community/relinks"), wrongRoll).andExpect(status().isForbidden());

        // A running SOS is never cut: refused until it ended.
        long sos = community.json(community.perform(p, post("/api/emergencies"), Map.of("triggerType", "MANUAL")))
                .get("id").asLong();
        Map<String, Object> right = Map.of("code", code.get("code").asText(), "rollNumber", "e123 7008");
        community.perform(admin, post("/api/admin/community/relinks"), right).andExpect(status().isConflict());
        community.perform(p, put("/api/emergencies/" + sos + "/cancel"), null).andExpect(status().isOk());

        community.perform(admin, post("/api/admin/community/relinks"), right).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RELINKED"));
        assertThat(users.findById(p.id()).orElseThrow().getPhoneE164()).isEqualTo(TestAccounts.e164For(newKey));
        community.me(p).andExpect(status().isUnauthorized());
        JsonNode session = TestAccounts.signIn(mockMvc, objectMapper, newKey, null);
        Account moved = new Account(session.get("accessToken").asText(), p.id(), newKey, p.name());
        community.me(moved).andExpect(jsonPath("$.state").value("MEMBER"));
        assertThat(relinks.findByPhoneE164(TestAccounts.e164For(newKey))).isEmpty();
        assertThat(community.json(community.perform(admin, get("/api/admin/community/log"), null)).toString())
                .contains("RELINKED");

        // A roll number without a member: refused (the person signs up and is verified instead).
        String otherTicket = TestAccounts.seedTicket(mockMvc,
                TestAccounts.e164For("community.new." + UUID.randomUUID() + "@example.com"));
        String other = community.json(mockMvc.perform(post("/api/community/relink")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("ticket", otherTicket, "rollNumber", "E1237077")))))
                .get("code").asText();
        community.perform(admin, post("/api/admin/community/relinks"),
                Map.of("code", other, "rollNumber", "E1237077")).andExpect(status().isConflict());
    }
}
