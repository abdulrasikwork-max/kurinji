package com.safesphere.backend.support;

import com.safesphere.backend.auth.phone.OtpSender;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Phase 17B tests: an [OtpSender] that keeps every code in memory (and can be told to fail, like a refusing provider). */
@TestConfiguration
public class CapturingOtpSenderConfig {

    public static final class CapturingOtpSender implements OtpSender {

        public record Sent(String phoneE164, String code) {
        }

        private final List<Sent> sent = new ArrayList<>();
        private boolean failing;

        @Override
        public synchronized void send(String phoneE164, String code) {
            if (failing) throw new OtpDeliveryException("test provider refused");
            sent.add(new Sent(phoneE164, code));
        }

        @Override
        public String describe() {
            return "CAPTURING (tests)";
        }

        public synchronized String lastCodeFor(String phoneE164) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).phoneE164().equals(phoneE164)) return sent.get(i).code();
            }
            return null;
        }

        public synchronized long countFor(String phoneE164) {
            return sent.stream().filter(s -> s.phoneE164().equals(phoneE164)).count();
        }

        public synchronized void setFailing(boolean failing) {
            this.failing = failing;
        }
    }

    @Bean
    @Primary
    public CapturingOtpSender capturingOtpSender() {
        return new CapturingOtpSender();
    }
}
