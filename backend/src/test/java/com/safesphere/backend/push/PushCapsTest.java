package com.safesphere.backend.push;

import static com.safesphere.backend.support.TestAccounts.bearer;
import static com.safesphere.backend.support.TestAccounts.registerVerifiedAndLogin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.push.entity.PushType;
import com.safesphere.backend.push.service.PushService;
import com.safesphere.backend.support.StubPushSenderConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** N7 (D30): social pushes stop at the per-person cap (Replies still has everything); safety and account never do. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubPushSenderConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:push-caps;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.push.social-per-hour=2",
        "app.push.social-per-day=3"})
class PushCapsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PushService push;

    @Test
    void socialPushesStopAtTheCap_safetyAndAccountDoNot() throws Exception {
        JsonNode answer = registerVerifiedAndLogin(mockMvc, objectMapper, "Asha Devi", "caps-" + UUID.randomUUID());
        long id = answer.get("user").get("id").asLong();
        mockMvc.perform(put("/api/push/device").header(HttpHeaders.AUTHORIZATION, bearer(answer.get("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", "cap:APA91b" + "y".repeat(120)))))
                .andExpect(status().isNoContent());
        assertThat(push.notify(id, PushType.REPLY_TURN, UUID.randomUUID().toString())).isTrue();
        assertThat(push.notify(id, PushType.PLAN_ADDED, UUID.randomUUID().toString())).isTrue();
        assertThat(push.notify(id, PushType.PLAN_MESSAGE, UUID.randomUUID().toString())).as("2 an hour").isFalse();
        assertThat(push.notify(id, PushType.CIRCLE_SOS, "9")).isTrue();
        assertThat(push.notify(id, PushType.ACCOUNT_SIGN_IN, "account")).isTrue();
        assertThat(push.notify(id, PushType.OMW_REMINDER, "10")).isTrue();
    }
}
