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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** N7 (D35): PUSH_PROVIDER=NONE is the kill switch: phones can still register, nothing is queued or sent. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:push-off;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.push.provider=NONE"})
class PushOffTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PushService push;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void withTheKillSwitchNothingIsQueued() throws Exception {
        JsonNode answer = registerVerifiedAndLogin(mockMvc, objectMapper, "Asha Devi", "off-" + UUID.randomUUID());
        long id = answer.get("user").get("id").asLong();
        mockMvc.perform(put("/api/push/device").header(HttpHeaders.AUTHORIZATION, bearer(answer.get("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", "off:APA91b" + "z".repeat(120)))))
                .andExpect(status().isNoContent());
        assertThat(push.notify(id, PushType.CIRCLE_SOS, "1")).isFalse();
        assertThat(push.notify(id, PushType.REPLY_TURN, UUID.randomUUID().toString())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from push_messages", Long.class)).isZero();
    }
}
