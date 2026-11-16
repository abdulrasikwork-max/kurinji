package com.safesphere.backend.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.Ban;
import com.safesphere.backend.community.entity.CommunityEnums.BanReason;
import com.safesphere.backend.community.entity.CommunityEnums.EndReason;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.repository.BanRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.VerificationRequestRepository;
import com.safesphere.backend.community.service.CommunityKeys;
import com.safesphere.backend.community.service.CommunityRules;
import com.safesphere.backend.community.service.IdPhotoStore;
import com.safesphere.backend.community.service.MembershipLookup;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase N3 (unit B2) through the real API: the decision by a person who saw the college ID. In person (the code claims
 * the request for its verifier; the roll number typed from the card must match; corrected names are logged and shown;
 * approval locks the name), by ID photo (admins only; the photo is served no-store and deleted at the decision), the
 * automatic refusals (ALREADY_MEMBER, BANNED), nobody verifies themselves, wrong-code and daily limits, verifier
 * appointment and removal (effective at once), admin name correction and the log.
 */
@SpringBootTest(properties = {"app.community.approvals-per-day=2", "app.community.wrong-codes=3"})
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class CommunityDecisionApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private UserRepository users;
    @Autowired private MembershipRepository memberships;
    @Autowired private VerificationRequestRepository requests;
    @Autowired private BanRepository bans;
    @Autowired private CommunityKeys keys;
    @Autowired private MembershipLookup lookup;
    @Autowired private IdPhotoStore photos;

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

    private int year() {
        return CommunityRules.firstYear(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) + 1;
    }

    private Account admin(String name) throws Exception {
        Account a = community.unverified(name);
        User user = users.findById(a.id()).orElseThrow();
        user.setRole(UserRole.ADMIN);
        users.saveAndFlush(user);
        return a;
    }

    private Account verifier(Account admin, String name) throws Exception {
        Account v = community.member(name);
        community.perform(admin, put("/api/admin/community/verifiers"),
                Map.of("phone", TestAccounts.e164For(v.key()), "verifier", true)).andExpect(status().isNoContent());
        return v;
    }

    /** Starts an in-person request; returns the code the applicant's screen shows. */
    private String inPerson(Account applicant, String roll) throws Exception {
        return community.json(community.start(applicant, "IN_PERSON", roll, "BE_CSE", year())
                .andExpect(status().isOk())).get("request").get("code").asText();
    }

    private String ref(Account decider, String code) throws Exception {
        return community.json(community.lookup(decider, code).andExpect(status().isOk())).get("ref").asText();
    }

    private static Map<String, Object> approve(String roll, String name) {
        Map<String, Object> body = new HashMap<>(Map.of("approve", true, "rollNumber", roll));
        if (name != null) body.put("name", name);
        return body;
    }

    private static Map<String, Object> refuse(String reason) {
        return Map.of("approve", false, "reason", reason);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // ---- in person ------------------------------------------------------------------------------------------------

    @Test
    void inPerson_approval_withACorrectedName_locksTheName_andOpensTheCommunity() throws Exception {
        Account admin = admin("Root Admin");
        Account verifier = verifier(admin, "Vani Verifier");
        Account asha = community.unverified("Asha Devy");
        String code = inPerson(asha, "E1236003");

        JsonNode detail = community.json(community.lookup(verifier, code.toLowerCase()).andExpect(status().isOk()));
        assertThat(detail.get("name").asText()).isEqualTo("Asha Devy");
        assertThat(detail.get("programme").asText()).isEqualTo("BE_CSE");
        assertThat(detail.get("phoneHint").asText()).doesNotContain(TestAccounts.phoneFor(asha.key()));
        assertThat(detail.toString()).doesNotContain("1236003");

        community.decideInPerson(verifier, detail.get("ref").asText(), approve(" e123 6003", "Asha Devi"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("VERIFIED"));

        community.me(asha).andExpect(jsonPath("$.state").value("MEMBER"))
                .andExpect(jsonPath("$.nameLocked").value(true))
                .andExpect(jsonPath("$.nameCorrection.from").value("Asha Devy"))
                .andExpect(jsonPath("$.membership.method").value("IN_PERSON"));
        assertThat(users.findById(asha.id()).orElseThrow().getName()).isEqualTo("Asha Devi");
        Membership m = memberships.findByUserId(asha.id()).orElseThrow();
        assertThat(m.getVerifiedBy()).isEqualTo(verifier.id());
        assertThat(m.getRollKey()).isEqualTo(keys.rollKey("MIET", "E1236003"));
        assertThat(m.getEndsAt()).isEqualTo(CommunityRules.endsAt(year()));
        assertThat(requests.findByUserId(asha.id())).isEmpty();
        community.perform(asha, get("/api/connections"), null).andExpect(status().isOk());

        // N-24: the person cannot change a checked name any more.
        community.perform(asha, put("/api/users/me/name"), Map.of("name", "Someone Else"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NAME_LOCKED"));

        // The log shows both decisions, for admins only.
        JsonNode log = community.json(community.perform(admin, get("/api/admin/community/log"), null)
                .andExpect(status().isOk()));
        assertThat(log.toString()).contains("VERIFIED").contains("NAME_CORRECTED").contains("Vani Verifier");
        community.perform(verifier, get("/api/admin/community/log"), null).andExpect(status().isForbidden());
    }

    @Test
    void aWrongRollNumber_isNotAccepted_andARefusalIsShownToTheApplicant() throws Exception {
        Account admin = admin("Root Admin");
        Account verifier = verifier(admin, "Vani Verifier");
        Account ravi = community.unverified("Ravi Kumar");
        String ref = ref(verifier, inPerson(ravi, "E1236011"));

        community.decideInPerson(verifier, ref, approve("E1236012", null)).andExpect(status().isConflict());
        community.decideInPerson(verifier, ref, Map.of("approve", false)).andExpect(status().isBadRequest());
        community.decideInPerson(verifier, ref, refuse("BANNED")) // set by the server only
                .andExpect(status().isBadRequest());
        community.decideInPerson(verifier, ref, refuse("ROLL_MISMATCH")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REFUSED"));

        community.me(ravi).andExpect(jsonPath("$.state").value("NONE"))
                .andExpect(jsonPath("$.outcome.kind").value("REFUSED"))
                .andExpect(jsonPath("$.outcome.reason").value("ROLL_MISMATCH"))
                .andExpect(jsonPath("$.nameLocked").value(false));
        assertThat(requests.findByUserId(ravi.id())).isEmpty();
        community.decideInPerson(verifier, ref, approve("E1236011", null)).andExpect(status().isNotFound());
    }

    @Test
    void onlyTheOneWhoTypedTheCode_decides_withinTheClaim_andWrongCodesAreLimited() throws Exception {
        Account admin = admin("Root Admin");
        Account first = verifier(admin, "First Verifier");
        Account second = verifier(admin, "Second Verifier");
        Account meera = community.unverified("Meera Nair");
        String ref = ref(first, inPerson(meera, "E1236013"));

        community.decideInPerson(second, ref, approve("E1236013", null)).andExpect(status().isNotFound());
        clock.advance(Duration.ofMinutes(16));
        community.decideInPerson(first, ref, approve("E1236013", null)).andExpect(status().isNotFound());
        String fresh = community.json(community.newCode(meera)).get("request").get("code").asText();
        community.decideInPerson(first, ref(first, fresh), approve("E1236013", null)).andExpect(status().isOk());

        // Wrong codes: 404 the same as an expired one, then 429 (limit 3 in this test).
        for (int i = 0; i < 3; i++) community.lookup(second, "ABCD-2345").andExpect(status().isNotFound());
        community.lookup(second, "ABCD-2345").andExpect(status().isTooManyRequests());
    }

    @Test
    void onlyVerifiersAndAdmins_decide_aRemovedVerifierStopsAtOnce_andNobodyVerifiesThemselves() throws Exception {
        Account admin = admin("Root Admin");
        Account otherAdmin = admin("Second Admin");
        Account member = community.member("Plain Member");
        Account outsider = community.unverified("Not Verified");
        community.lookup(member, "ABCD-2345").andExpect(status().isForbidden());
        community.lookup(outsider, "ABCD-2345").andExpect(status().isForbidden());

        Account verifier = verifier(admin, "Vani Verifier");
        community.lookup(verifier, "ABCD-2345").andExpect(status().isNotFound());
        community.perform(admin, put("/api/admin/community/verifiers"),
                Map.of("phone", TestAccounts.e164For(verifier.key()), "verifier", false))
                .andExpect(status().isNoContent());
        community.lookup(verifier, "ABCD-2345").andExpect(status().isForbidden());

        // Bootstrap: the root admins are not members yet; each is verified by another, never by themselves.
        String own = inPerson(admin, "E1236001");
        community.lookup(admin, own).andExpect(status().isForbidden());
        community.decideInPerson(otherAdmin, ref(otherAdmin, own), approve("E1236001", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("VERIFIED"));
        community.me(admin).andExpect(jsonPath("$.state").value("MEMBER"))
                .andExpect(jsonPath("$.staff.admin").value(true));

        // Only active members can be verifiers.
        community.perform(admin, put("/api/admin/community/verifiers"),
                Map.of("phone", TestAccounts.e164For(outsider.key()), "verifier", true))
                .andExpect(status().isConflict());
        community.perform(member, put("/api/admin/community/verifiers"),
                Map.of("phone", TestAccounts.e164For(member.key()), "verifier", true))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRollNumberThatHasAnAccount_orIsBanned_isRefusedByTheServer() throws Exception {
        Account admin = admin("Root Admin");
        Account verifier = verifier(admin, "Vani Verifier");
        Account first = community.unverified("First Person");
        community.decideInPerson(verifier, ref(verifier, inPerson(first, "E1236050")), approve("E1236050", null))
                .andExpect(status().isOk());

        Account second = community.unverified("Second Person");
        community.decideInPerson(verifier, ref(verifier, inPerson(second, "E1236050")), approve("E1236050", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("REFUSED"))
                .andExpect(jsonPath("$.reason").value("ALREADY_MEMBER"));
        community.me(second).andExpect(jsonPath("$.outcome.reason").value("ALREADY_MEMBER"));

        Account third = community.unverified("Third Person");
        bans.saveAndFlush(new Ban(lookup.miet().getId(), keys.rollKey("MIET", "E1236051"), null, BanReason.HATE, null,
                admin.id(), now()));
        community.decideInPerson(verifier, ref(verifier, inPerson(third, "E1236051")), approve("E1236051", null))
                .andExpect(jsonPath("$.reason").value("BANNED"));
        assertThat(memberships.findByUserId(third.id())).isEmpty();
    }

    @Test
    void aVerifierHasADailyLimit_adminsDoNot() throws Exception {
        Account admin = admin("Root Admin");
        Account verifier = verifier(admin, "Vani Verifier");
        for (int i = 0; i < 2; i++) {
            Account p = community.unverified("Person " + (char) ('A' + i));
            community.decideInPerson(verifier, ref(verifier, inPerson(p, "E12361" + i + "0")),
                    approve("E12361" + i + "0", null)).andExpect(status().isOk());
        }
        Account third = community.unverified("Person Third");
        String ref = ref(verifier, inPerson(third, "E1236190"));
        community.decideInPerson(verifier, ref, approve("E1236190", null)).andExpect(status().isTooManyRequests());
        String code = community.json(community.newCode(third)).get("request").get("code").asText();
        community.decideInPerson(admin, ref(admin, code), approve("E1236190", null)).andExpect(status().isOk());
    }

    @Test
    void anEndedMembership_canBeVerifiedAgain_reusingItsRow() throws Exception {
        Account admin = admin("Root Admin");
        Account verifier = verifier(admin, "Vani Verifier");
        Account p = community.unverified("Kavya Raj");
        community.decideInPerson(verifier, ref(verifier, inPerson(p, "E1236060")), approve("E1236060", null))
                .andExpect(status().isOk());
        Membership m = memberships.findByUserId(p.id()).orElseThrow();
        m.end(EndReason.REMOVED, now());
        memberships.saveAndFlush(m);
        community.me(p).andExpect(jsonPath("$.state").value("ENDED"));
        community.decideInPerson(verifier, ref(verifier, inPerson(p, "E1236060")), approve("E1236060", null))
                .andExpect(jsonPath("$.outcome").value("VERIFIED"));
        assertThat(memberships.findByUserId(p.id()).orElseThrow().getId()).isEqualTo(m.getId());
        community.me(p).andExpect(jsonPath("$.state").value("MEMBER"));
    }

    // ---- ID photo -------------------------------------------------------------------------------------------------

    @Test
    void idPhoto_onlyAdminsSeeIt_noStore_andTheFileIsDeletedAtTheDecision() throws Exception {
        Account admin = admin("Root Admin");
        Account verifier = verifier(admin, "Vani Verifier");
        Account ravi = community.unverified("Ravi Kumar");
        community.start(ravi, "ID_PHOTO", "E1236070", "MBA", year()).andExpect(status().isOk());
        community.uploadPhoto(ravi, TestImages.jpeg(300, 200), MediaType.IMAGE_JPEG).andExpect(status().isOk());
        String key = requests.findByUserId(ravi.id()).orElseThrow().getPhotoKey();

        community.photoQueue(verifier).andExpect(status().isForbidden());
        JsonNode queue = community.json(community.photoQueue(admin).andExpect(status().isOk()));
        String ref = null;
        for (JsonNode item : queue) if (item.get("name").asText().equals("Ravi Kumar")) ref = item.get("ref").asText();
        assertThat(ref).isNotNull();
        community.photo(verifier, ref).andExpect(status().isForbidden());
        byte[] jpeg = community.photo(admin, ref).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);

        // A verifier cannot decide a photo request; the admin can.
        community.decideInPerson(verifier, ref, approve("E1236070", null)).andExpect(status().isNotFound());
        community.decideAsAdmin(admin, ref, approve("E1236070", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("VERIFIED"));
        assertThat(Files.exists(photos.root().resolve(key + ".idp"))).isFalse();
        community.photo(admin, ref).andExpect(status().isNotFound());
        community.me(ravi).andExpect(jsonPath("$.membership.method").value("ID_PHOTO"));

        // A refusal deletes the photo too.
        Account kavin = community.unverified("Kavin Raj");
        community.start(kavin, "ID_PHOTO", "E1236071", "MBA", year()).andExpect(status().isOk());
        community.uploadPhoto(kavin, TestImages.png(300, 200), MediaType.IMAGE_PNG).andExpect(status().isOk());
        String second = requests.findByUserId(kavin.id()).orElseThrow().getPhotoKey();
        String secondRef = requests.findByUserId(kavin.id()).orElseThrow().getPublicId();
        community.decideAsAdmin(admin, secondRef, refuse("UNREADABLE")).andExpect(status().isOk());
        assertThat(Files.exists(photos.root().resolve(second + ".idp"))).isFalse();
        community.me(kavin).andExpect(jsonPath("$.outcome.reason").value("UNREADABLE"));
    }

    @Test
    void anAdminCorrectsALockedName_loggedAndShown() throws Exception {
        Account admin = admin("Root Admin");
        Account verifier = verifier(admin, "Vani Verifier");
        Account p = community.unverified("Selvi Mani");
        community.decideInPerson(verifier, ref(verifier, inPerson(p, "E1236080")), approve("E1236080", null))
                .andExpect(status().isOk());
        community.perform(p, put("/api/users/me/name"), Map.of("name", "Selvi M")).andExpect(status().isConflict());
        community.perform(admin, put("/api/admin/community/members/name"),
                Map.of("phone", TestAccounts.e164For(p.key()), "name", "Selvi Manikandan"))
                .andExpect(status().isNoContent());
        assertThat(users.findById(p.id()).orElseThrow().getName()).isEqualTo("Selvi Manikandan");
        community.me(p).andExpect(jsonPath("$.nameCorrection.from").value("Selvi Mani"));
        community.perform(admin, put("/api/admin/community/members/name"),
                Map.of("phone", "9000000001", "name", "Nobody Here")).andExpect(status().isNotFound());
        JsonNode verifiers = community.json(community.perform(admin, get("/api/admin/community/verifiers"), null)
                .andExpect(status().isOk()));
        assertThat(verifiers.toString()).contains("Vani Verifier").doesNotContain(TestAccounts.phoneFor(verifier.key()));
    }
}
