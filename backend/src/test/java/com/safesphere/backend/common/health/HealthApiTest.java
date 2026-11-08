package com.safesphere.backend.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

/** Phase D12: the health endpoint is public, says only UP or DOWN, and is GET only. */
@SpringBootTest
@AutoConfigureMockMvc
class HealthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void the_health_check_needs_no_sign_in_and_answers_exactly_UP() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"status\":\"UP\"}"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void only_GET_is_allowed_and_a_made_up_sub_path_is_still_protected() throws Exception {
        mockMvc.perform(post("/api/health")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/health/details")).andExpect(status().isUnauthorized());
    }

    @Test
    void a_database_that_does_not_answer_gives_503_DOWN_without_any_reason() throws Exception {
        HealthController broken = new HealthController(new FailingDataSource());
        var response = broken.health();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsExactly(org.assertj.core.api.Assertions.entry("status", "DOWN"));
        assertThat(String.valueOf(response.getBody())).doesNotContain("refused", "Exception", "jdbc");
    }

    @Test
    void a_connection_that_is_not_valid_is_also_DOWN() {
        HealthController invalid = new HealthController(new DataSourceWithInvalidConnection());
        assertThat(invalid.health().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** getConnection fails, as when MySQL is down. */
    private static class FailingDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException { throw new SQLException("jdbc: connection refused"); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("no"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    /** A connection whose isValid answers false (a dead pooled connection). */
    private static class DataSourceWithInvalidConnection extends FailingDataSource {
        @Override
        public Connection getConnection() {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("isValid")) return false;
                        if (method.getName().equals("close")) return null;
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
