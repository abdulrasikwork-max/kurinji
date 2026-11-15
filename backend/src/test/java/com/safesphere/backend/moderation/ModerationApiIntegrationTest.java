package com.safesphere.backend.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.moderation.repository.ModerationActionRepository;
import com.safesphere.backend.moderation.repository.ModerationAppealRepository;
import com.safesphere.backend.support.ApiTestClient;
import com.safesphere.backend.support.ApiTestClient.Account;
import com.safesphere.backend.support.MutableClock;
import com.safesphere.backend.support.MutableClockConfig;
import com.safesphere.backend.support.TestAccounts;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The shared moderation foundation through the real API: who may moderate and administer, role changes, and the audit
 * log. (Until N1 this class also covered the Phase 7 Nearby signal cases; those are retired, history in git tag
 * pre-n1. Content cases, decisions and appeals are covered by the content moderation tests.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
class ModerationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private ModerationActionRepository actions;
    @Autowired private ModerationAppealRepository appeals;
    @Autowired private UserRepository users;

    private ApiTestClient api;

    @BeforeEach
    void clean() {
        clock.reset();
        appeals.deleteAll();
        actions.deleteAll();
        api = new ApiTestClient(mockMvc, objectMapper);
    }

    // ---- Who may moderate ---------------------------------------------------------------------------------------------

    @Test
    void normalUsers_cannotReachModerationOrAdministration_andNobodyWithoutAToken() throws Exception {
        Account user = api.account();
        api.getAs(user, "/api/moderation/content-cases").andExpect(status().isForbidden());
        api.getAs(user, "/api/moderation/content-appeals").andExpect(status().isForbidden());
        api.getAs(user, "/api/moderation/content-cases/" + UUID.randomUUID()).andExpect(status().isForbidden());
        api.postAs(user, "/api/moderation/content-cases/" + UUID.randomUUID() + "/actions",
                Map.of("action", "CONTENT_KEEP", "reason", "NO_VIOLATION")).andExpect(status().isForbidden());
        api.putAs(user, "/api/admin/roles", Map.of("phone", TestAccounts.e164For(user.email()), "role", "MODERATOR"))
                .andExpect(status().isForbidden());
        api.getAs(user, "/api/admin/moderation-log").andExpect(status().isForbidden());

        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        api.getAs(moderator, "/api/admin/moderation-log").andExpect(status().isForbidden());
        api.putAs(moderator, "/api/admin/roles", Map.of("phone", TestAccounts.e164For(user.email()), "role", "MODERATOR"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/moderation/content-cases")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/moderation/access")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/moderation-log")).andExpect(status().isUnauthorized());
        assertEquals(UserRole.USER, users.findById(user.id()).orElseThrow().getRole(), "nothing changed");
    }

    @Test
    void access_tellsTheAppWhatToShow() throws Exception {
        api.getAs(api.account(), "/api/moderation/access").andExpect(status().isOk())
                .andExpect(jsonPath("$.canModerate").value(false)).andExpect(jsonPath("$.canAdminister").value(false));
        api.getAs(withRole(api.account(), UserRole.MODERATOR), "/api/moderation/access")
                .andExpect(jsonPath("$.canModerate").value(true)).andExpect(jsonPath("$.canAdminister").value(false));
        api.getAs(withRole(api.account(), UserRole.ADMIN), "/api/moderation/access")
                .andExpect(jsonPath("$.canModerate").value(true)).andExpect(jsonPath("$.canAdminister").value(true));
    }

    @Test
    void aRoleCannotBeClaimedByTheClient() throws Exception {
        String phone = TestAccounts.e164For("claims.admin." + UUID.randomUUID());
        String ticket = TestAccounts.seedTicket(mockMvc, phone);
        mockMvc.perform(post("/api/auth/sign-up").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Claims Admin", "ticket", ticket,
                                "role", "ADMIN", "dateOfBirth", TestAccounts.ADULT_BIRTH_DATE))))
                .andExpect(status().isCreated());
        assertEquals(UserRole.USER, users.findByPhoneE164AndPhoneVerifiedAtIsNotNull(phone).orElseThrow().getRole());
    }

    @Test
    void theRetiredNearbyEndpoints_areGone() throws Exception {
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        Account admin = withRole(api.account(), UserRole.ADMIN);
        api.getAs(moderator, "/api/moderation/cases").andExpect(status().isNotFound());
        api.getAs(moderator, "/api/moderation/appeals").andExpect(status().isNotFound());
        api.getAs(admin, "/api/admin/evaluation").andExpect(status().isNotFound());
        api.getAs(admin, "/api/community/area?cell=tdr1v9").andExpect(status().isNotFound());
    }

    // ---- Roles and the audit log -------------------------------------------------------------------------------------

    @Test
    void roleChanges_takeEffectOnTheVeryNextRequest_withTheSameToken_andAreAudited() throws Exception {
        Account admin = withRole(api.account(), UserRole.ADMIN);
        Account helper = api.account();
        api.getAs(helper, "/api/moderation/content-cases").andExpect(status().isForbidden());

        api.putAs(admin, "/api/admin/roles", Map.of("phone", "+91 " + TestAccounts.phoneFor(helper.email()), "role", "MODERATOR"))
                .andExpect(status().isNoContent());
        api.getAs(helper, "/api/moderation/content-cases").andExpect(status().isOk());

        api.putAs(admin, "/api/admin/roles", Map.of("phone", TestAccounts.e164For(helper.email()), "role", "USER")).andExpect(status().isNoContent());
        api.getAs(helper, "/api/moderation/content-cases").andExpect(status().isForbidden());

        api.putAs(admin, "/api/admin/roles", Map.of("phone", TestAccounts.e164For(helper.email()), "role", "ADMIN")).andExpect(status().isBadRequest());
        api.putAs(admin, "/api/admin/roles", Map.of("phone", TestAccounts.e164For(admin.email()), "role", "USER")).andExpect(status().isConflict());
        Account otherAdmin = withRole(api.account(), UserRole.ADMIN);
        api.putAs(admin, "/api/admin/roles", Map.of("phone", TestAccounts.e164For(otherAdmin.email()), "role", "USER")).andExpect(status().isConflict());
        api.putAs(admin, "/api/admin/roles", Map.of("phone", TestAccounts.e164For("nobody." + UUID.randomUUID()), "role", "MODERATOR"))
                .andExpect(status().isNotFound());

        JsonNode log = api.json(api.getAs(admin, "/api/admin/moderation-log").andExpect(status().isOk()));
        assertEquals(2, log.size(), "the refused changes leave no entry");
        assertEquals("ROLE_REVOKED", log.get(0).get("action").asText());
        assertEquals("ROLE_GRANTED", log.get(1).get("action").asText());
        assertEquals(helper.id(), log.get(1).get("subjectUserId").asLong());
        for (JsonNode entry : log) {
            assertEquals(admin.id(), entry.get("actorId").asLong());
            assertTrue(entry.get("targetKind").isNull(), "a role change is about no content");
            assertOnly(entry, "id", "actorId", "action", "reason", "caseRef", "targetKind", "subjectUserId",
                    "restrictedUntil", "newRole", "occurredAt");
        }
        assertFalse(log.toString().contains(helper.email()) || log.toString().contains(admin.email()),
                "the log names account ids, never e-mails");
    }

    @Test
    void aDisabledModerator_hasNoAccess() throws Exception {
        Account moderator = withRole(api.account(), UserRole.MODERATOR);
        User user = users.findById(moderator.id()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "enabled", false);
        users.save(user);
        // Phase 10A: the per-request account check (ActiveAccountCheck) rejects a disabled account's token on every
        // endpoint (401), before the role check is reached.
        api.getAs(moderator, "/api/moderation/content-cases").andExpect(status().isUnauthorized());
        api.getAs(moderator, "/api/moderation/access").andExpect(status().isUnauthorized());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    private Account withRole(Account account, UserRole role) {
        User user = users.findById(account.id()).orElseThrow();
        user.setRole(role);
        users.save(user);
        return account;
    }

    private static void assertOnly(JsonNode node, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            assertTrue(names.contains(field), "unexpected field: " + field);
        }
    }
}
