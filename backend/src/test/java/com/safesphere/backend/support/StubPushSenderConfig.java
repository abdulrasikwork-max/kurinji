package com.safesphere.backend.support;

import com.safesphere.backend.push.service.PushSender;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** N7: replaces the push sender in a test: records what would have been sent, and answers what the test scripts. */
@TestConfiguration
public class StubPushSenderConfig {

    public static final class StubPushSender implements PushSender {

        private final List<Push> sent = new ArrayList<>();
        private final Deque<Outcome> scripted = new ArrayDeque<>();

        @Override
        public synchronized Outcome send(Push push) {
            Outcome next = scripted.isEmpty() ? Outcome.accepted() : scripted.pollFirst();
            if (next.result() == Result.ACCEPTED) sent.add(push);
            return next;
        }

        @Override
        public String describe() {
            return "STUB";
        }

        /** The next answers, in order; after them every push is accepted. */
        public synchronized void answer(Outcome... outcomes) {
            scripted.addAll(List.of(outcomes));
        }

        public synchronized List<Push> sent() {
            return List.copyOf(sent);
        }

        public synchronized void reset() {
            sent.clear();
            scripted.clear();
        }
    }

    @Bean
    @Primary
    public StubPushSender stubPushSender() {
        return new StubPushSender();
    }
}
