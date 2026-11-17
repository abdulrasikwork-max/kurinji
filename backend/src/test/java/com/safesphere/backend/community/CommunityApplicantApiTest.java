package com.safesphere.backend.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.community.entity.Ban;
import com.safesphere.backend.community.entity.CommunityEnums.BanReason;
import com.safesphere.backend.community.entity.Membership;
import com.safesphere.backend.community.repository.BanRepository;
import com.safesphere.backend.community.repository.MembershipRepository;
import com.safesphere.backend.community.repository.VerificationRequestRepository;
import com.safesphere.backend.community.service.CommunityKeys;
import com.safesphere.backend.community.service.CommunityRules;
import com.safesphere.backend.community.service.IdPhotoStore;
import com.safesphere.backend.community.service.MembershipLookup;
import com.safesphere.backend.community.entity.CommunityEnums.EndReason;
import com.safesphere.backend.support.CommunityClient;
import com.safesphere.backend.support.CommunityClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.support.TestImages;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase N3 (unit B1) through the real API: the applicant's side of verification (one way at a time, the in-person
 * code, the ID photo, withdrawal, expiry), what the person is shown, the roll number never stored as text, and the
 * membership gate on the social features (an unverified person keeps their own profile draft only and is seen by
 * nobody).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class CommunityApplicantApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private VerificationRequestRepository requests;
    @Autowired private MembershipRepository memberships;
    @Autowired private BanRepository bans;
    @Autowired private CommunityKeys keys;
    @Autowired private MembershipLookup lookup;
    @Autowired private IdPhotoStore photos;
    @Autowired private JdbcTemplate jdbc;

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

    private int year() {
        return CommunityRules.firstYear(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) + 2;
    }

    @Test
    void anUnverifiedPerson_seesNone_andTheProgrammeList() throws Exception {
        Account asha = community.unverified("Asha Devi");
        community.me(asha).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NONE"))
                .andExpect(jsonPath("$.community.code").value("MIET"))
                .andExpect(jsonPath("$.nameLocked").value(false))
                .andExpect(jsonPath("$.staff.verifier").value(false))
                .andExpect(jsonPath("$.staff.admin").value(false));
        JsonNode programmes = community.json(community.programmes(asha).andExpect(status().isOk()));
        assertThat(programmes).hasSize(14);
        assertThat(programmes.get(0).get("label").asText()).isEqualTo("B.E. Computer Science & Engineering");
        assertThat(programmes.toString()).doesNotContain("seat");
    }

    @Test
    void inPerson_isTheDefault_givesACodeOnce_andTheRollNumberIsNeverStoredAsText() throws Exception {
        Account asha = community.unverified("Asha Devi");
        JsonNode answer = community.json(community.start(asha, null, " e123-6003 ", "BTECH_IT", year())
                .andExpect(status().isOk()));
        assertThat(answer.get("state").asText()).isEqualTo("PENDING");
        assertThat(answer.get("request").get("method").asText()).isEqualTo("IN_PERSON");
        String code = answer.get("request").get("code").asText();
        assertThat(code).matches("^[2-9A-HJKMNP-Z]{4}-[2-9A-HJKMNP-Z]{4}$");
        assertThat(answer.get("request").get("codeAlive").asBoolean()).isTrue();
        assertThat(answer.get("request").get("codeExpiresInSeconds").asLong()).isBetween(590L, 600L);
        assertThat(answer.toString()).doesNotContain("1236003");

        // Later answers say only that a code is alive (only its HMAC is stored).
        JsonNode later = community.json(community.me(asha).andExpect(status().isOk()));
        assertThat(later.get("request").get("code").isNull()).isTrue();
        assertThat(later.get("request").get("codeAlive").asBoolean()).isTrue();

        // Nothing in the row is the roll number or the code; the roll key is the HMAC of the normalised number.
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM verification_requests WHERE user_id = ?", asha.id());
        assertThat(row.values().toString()).doesNotContain("1236003").doesNotContain(code.replace("-", ""));
        assertThat(row.get("ROLL_KEY")).isEqualTo(keys.rollKey("MIET", "E1236003"));

        // One request at a time; a new code replaces the old one.
        community.start(asha, "ID_PHOTO", "E1236003", "BTECH_IT", year()).andExpect(status().isConflict());
        String second = community.json(community.newCode(asha).andExpect(status().isOk()))
                .get("request").get("code").asText();
        assertThat(second).isNotEqualTo(code);

        // The code dies after 10 minutes; the request itself stays until it expires.
        clock.advance(Duration.ofMinutes(11));
        community.me(asha).andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.request.codeAlive").value(false));
    }

    @Test
    void wrongInput_isRefusedWithWordsThePersonUnderstands() throws Exception {
        Account asha = community.unverified("Asha Devi");
        community.start(asha, "IN_PERSON", "ABCDEFG", "BTECH_IT", year()).andExpect(status().isBadRequest());
        community.start(asha, "IN_PERSON", "E1236003", "B_ARCH", year()).andExpect(status().isBadRequest());
        community.start(asha, "IN_PERSON", "E1236003", "MBA", year() + 3).andExpect(status().isBadRequest());
        community.start(asha, "IN_PERSON", "E1236003", "BTECH_IT", 2001).andExpect(status().isBadRequest());
        community.start(asha, "IN_PERSON", "E1236003", "BTECH_IT", null).andExpect(status().isBadRequest());
        community.start(asha, "BOTH", "E1236003", "BTECH_IT", year()).andExpect(status().isBadRequest());
        community.me(asha).andExpect(jsonPath("$.state").value("NONE"));
    }

    @Test
    void idPhoto_isReEncodedAndEncrypted_replacedPhotosGo_andWithdrawalDeletesTheFile() throws Exception {
        Account ravi = community.unverified("Ravi Kumar");
        JsonNode started = community.json(community.start(ravi, "ID_PHOTO", "E1236010", "BE_ECE", year())
                .andExpect(status().isOk()));
        assertThat(started.get("request").get("code").isNull()).isTrue();
        assertThat(started.get("request").get("photoUploaded").asBoolean()).isFalse();

        community.uploadPhoto(ravi, TestImages.jpegWithExifGps(400, 250), MediaType.IMAGE_JPEG)
                .andExpect(status().isOk()).andExpect(jsonPath("$.request.photoUploaded").value(true));
        String first = requests.findByUserId(ravi.id()).orElseThrow().getPhotoKey();
        Path file = photos.root().resolve(first + ".idp");
        byte[] stored = Files.readAllBytes(file);
        assertThat(stored[0]).as("version byte, not a JPEG").isEqualTo((byte) 1);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(TestImages.EXIF_MARKER).doesNotContain("JFIF");
        byte[] plain = photos.read(first);
        assertThat(plain[0]).isEqualTo((byte) 0xFF);
        assertThat(TestImages.containsExifSegment(plain)).isFalse();

        community.uploadPhoto(ravi, TestImages.png(300, 200), MediaType.IMAGE_PNG).andExpect(status().isOk());
        String second = requests.findByUserId(ravi.id()).orElseThrow().getPhotoKey();
        assertThat(second).isNotEqualTo(first);
        assertThat(Files.exists(file)).as("the replaced photo is deleted").isFalse();

        community.uploadPhoto(ravi, "not an image".getBytes(), MediaType.IMAGE_JPEG).andExpect(status().isBadRequest());
        community.newCode(ravi).andExpect(status().isNotFound());

        community.withdraw(ravi).andExpect(status().isNoContent());
        assertThat(Files.exists(photos.root().resolve(second + ".idp"))).isFalse();
        assertThat(requests.findByUserId(ravi.id())).isEmpty();
        community.withdraw(ravi).andExpect(status().isNoContent());
        community.me(ravi).andExpect(jsonPath("$.state").value("NONE"));
        // An in-person request has no photo.
        community.start(ravi, "IN_PERSON", "E1236010", "BE_ECE", year()).andExpect(status().isOk());
        community.uploadPhoto(ravi, TestImages.png(300, 200), MediaType.IMAGE_PNG).andExpect(status().isNotFound());
    }

    @Test
    void anExpiredRequest_canBeReplaced() throws Exception {
        Account asha = community.unverified("Asha Devi");
        community.start(asha, "IN_PERSON", "E1236003", "BTECH_IT", year()).andExpect(status().isOk());
        clock.advance(Duration.ofDays(8));
        community.me(asha).andExpect(jsonPath("$.state").value("NONE"));
        community.start(asha, "ID_PHOTO", "E1236003", "BTECH_IT", year()).andExpect(status().isOk())
                .andExpect(jsonPath("$.request.method").value("ID_PHOTO"));
    }

    @Test
    void aMember_cannotAskAgain_andABannedNumberIsRefused_andShownAsBanned() throws Exception {
        Account member = community.member("Meena Raj");
        community.me(member).andExpect(jsonPath("$.state").value("MEMBER"))
                .andExpect(jsonPath("$.membership.programme").value("BTECH_IT"));
        community.start(member, "IN_PERSON", "E1236020", "BTECH_IT", year()).andExpect(status().isConflict());

        Account banned = community.unverified("Kavin Raj");
        long miet = lookup.miet().getId();
        bans.saveAndFlush(new Ban(miet, null, keys.phoneKey(TestAccounts.e164For(banned.key())), BanReason.HARASSMENT,
                null, member.id(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
        community.start(banned, "IN_PERSON", "E1236021", "BTECH_IT", year()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMUNITY_BANNED"));
        community.me(banned).andExpect(jsonPath("$.state").value("BANNED"))
                .andExpect(jsonPath("$.outcome.reason").value("HARASSMENT"));
    }

    @Test
    void theGate_unverifiedPeopleKeepTheirOwnDraft_seeNothing_andAreSeenByNobody() throws Exception {
        Account member = community.member("Meena Raj");
        Account outsider = community.unverified("Asha Devi");
        String handle = "asha_x" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);

        // The own profile draft works without a membership.
        community.perform(outsider, put("/api/profiles/me"), Map.of("handle", handle)).andExpect(status().isOk());
        community.perform(outsider, get("/api/profiles/me"), null).andExpect(status().isOk());
        // Everything about others answers MEMBERSHIP_NEEDED.
        for (String path : List.of("/api/moments/today", "/api/moments/mine", "/api/connections",
                "/api/profiles/by-handle/" + handle, "/api/blocks")) {
            community.perform(outsider, get(path), null).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MEMBERSHIP_NEEDED"));
        }
        // A member cannot see an unverified person.
        community.perform(member, get("/api/profiles/by-handle/" + handle), null).andExpect(status().isNotFound());

        // Membership ends (graduation passed, or an admin): the same as never verified, both ways.
        Membership own = memberships.findByUserId(member.id()).orElseThrow();
        community.perform(member, get("/api/connections"), null).andExpect(status().isOk());
        own.end(EndReason.REMOVED, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        memberships.saveAndFlush(own);
        community.perform(member, get("/api/connections"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_NEEDED"));
        community.me(member).andExpect(jsonPath("$.state").value("ENDED"))
                .andExpect(jsonPath("$.outcome.reason").value("REMOVED"));
    }

    @Test
    void graduation_stopsAccessAtTheEndOf31July_beforeAnyJobRuns() throws Exception {
        Account member = community.member("Meena Raj");
        Membership own = memberships.findByUserId(member.id()).orElseThrow();
        community.perform(member, get("/api/connections"), null).andExpect(status().isOk());
        Duration untilEnd = Duration.between(clock.instant(), own.getEndsAt().toInstant(ZoneOffset.UTC));
        clock.advance(untilEnd.minusMinutes(1));
        community.perform(member, get("/api/connections"), null).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(1));
        community.perform(member, get("/api/connections"), null).andExpect(status().isForbidden());
        community.me(member).andExpect(jsonPath("$.state").value("ENDED"))
                .andExpect(jsonPath("$.outcome.reason").value("GRADUATED"));
    }
}
