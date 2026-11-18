package com.safesphere.backend.support;

import com.safesphere.backend.common.sms.SmsGateway;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** N6: replaces the alert SMS gateway in a test: records what would have been sent, or refuses everything. */
@TestConfiguration
public class StubSmsGatewayConfig {

    public static final class StubSmsGateway implements SmsGateway {

        public record Sent(String phoneE164, String text) {
        }

        private final List<Sent> sent = new ArrayList<>();
        private boolean refusing;
        private int calls;

        @Override
        public synchronized String send(String phoneE164, String text) {
            calls++;
            if (refusing) throw new SmsRefusedException("test provider refused (status 416)");
            sent.add(new Sent(phoneE164, text));
            return "stub-" + sent.size();
        }

        @Override
        public String describe() {
            return "STUB";
        }

        public synchronized List<Sent> sent() {
            return List.copyOf(sent);
        }

        public synchronized List<String> textsTo(String phoneE164) {
            return sent.stream().filter(s -> s.phoneE164().equals(phoneE164)).map(Sent::text).toList();
        }

        public synchronized int calls() {
            return calls;
        }

        public synchronized void refuse(boolean refusing) {
            this.refusing = refusing;
        }

        public synchronized void reset() {
            sent.clear();
            refusing = false;
            calls = 0;
        }
    }

    @Bean
    @Primary
    public StubSmsGateway stubSmsGateway() {
        return new StubSmsGateway();
    }
}
