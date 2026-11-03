package com.safesphere.backend.explore;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.config.ExploreUnderstandingProperties;
import com.safesphere.backend.explore.UnderstandingClient.Off;
import com.safesphere.backend.explore.UnderstandingClient.Outcome;
import com.safesphere.backend.explore.UnderstandingClient.Understood;
import com.safesphere.backend.explore.UnderstandingClient.Unusable;
import com.safesphere.backend.explore.UnderstandingDtos.UnderstandResponse;
import com.safesphere.backend.explore.UnderstandingDtos.UnderstandingStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Phase 17G-3: the optional understanding. It decides NOTHING that people see: it asks the internal service once, and every
 * way it can fail ends in the same place for the app (the phone's own word list). In order: switched on? the text fit to
 * send? our own caps and breaker allow a call? then one call; any failure opens the breaker.
 */
@Service
public class UnderstandingService {

    static final int MAX_TEXT = 200;
    /**
     * EXACTLY the characters the internal service refuses (understand.py _CONTROL): tab and line feed are allowed, every other
     * control character is not. The two must agree, or a text this side lets through would come back as a service error and
     * wrongly open the breaker for everybody.
     */
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0b-\\x1f\\x7f]");

    private final ExploreUnderstandingProperties properties;
    private final UnderstandingGate gate;
    private final UnderstandingClient client;
    private final Clock clock;

    private Instant statusCheckedAt = Instant.MIN;
    private boolean statusValue;

    public UnderstandingService(ExploreUnderstandingProperties properties, UnderstandingGate gate,
                                UnderstandingClient client, Clock clock) {
        this.properties = properties;
        this.gate = gate;
        this.client = client;
        this.clock = clock;
    }

    /** Whether to offer the switch: switched on here AND the internal service holds a provider key (remembered briefly). */
    public UnderstandingStatus status() {
        if (!properties.active()) return new UnderstandingStatus(false);
        synchronized (this) {
            Instant now = clock.instant();
            if (now.isAfter(statusCheckedAt.plus(properties.statusCache()))) {
                statusValue = client.serviceHasKey();
                statusCheckedAt = now;
            }
            return new UnderstandingStatus(statusValue);
        }
    }

    public UnderstandResponse understand(long userId, String rawText) {
        if (!properties.active()) throw UnderstandingRefusedException.off();
        String text = rawText == null ? "" : rawText.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT || CONTROL.matcher(text).find()) {
            throw new InvalidRequestException("Please type up to 200 characters of plain text");
        }
        if (gate.tryAcquire(userId) != UnderstandingGate.Verdict.ALLOWED) throw UnderstandingRefusedException.unavailable();
        Outcome outcome = client.understand(text);
        if (outcome instanceof Understood understood) {
            return new UnderstandResponse(understood.categories(), understood.facets(), understood.daypart());
        }
        if (outcome instanceof Off) throw UnderstandingRefusedException.off();
        // This request alone got no usable answer: the call counts against the caps (it was made) but says nothing about the
        // provider, so it must not open the breaker for everybody.
        if (outcome instanceof Unusable) throw UnderstandingRefusedException.unavailable();
        gate.failed();
        throw UnderstandingRefusedException.unavailable();
    }
}
