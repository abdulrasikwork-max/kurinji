package com.safesphere.backend.push.service;

import com.safesphere.backend.push.entity.PushType;
import java.time.Duration;
import java.util.Map;

/**
 * N7 (D26 = N-118): the port to the push provider. A push is a POINTER only: {@link #data} is everything that leaves IRL
 * besides the token and the time (PushPayloadTest pins it). Implementations: FcmClient (production), DevFilePushSender
 * (development; the prod profile refuses it) and a stub in the tests.
 */
public interface PushSender {

    /** One message for one phone. */
    record Push(String token, PushType type, String subject, String eventId, boolean highPriority, long ttlSeconds,
                String collapseKey) {

        /** The data block: type, opaque subject id, event id, version. Nothing else, ever. */
        public Map<String, String> data() {
            return Map.of("t", type.name(), "s", subject, "m", eventId, "v", "1");
        }

        /** Never prints the token. */
        @Override
        public String toString() {
            return "Push[" + type + "]";
        }
    }

    enum Result {
        /** The provider took it (not: the phone showed it). */
        ACCEPTED,
        /** The token is no longer valid: delete the device. */
        UNREGISTERED,
        /** Try again later (rate limit, provider unavailable, network). */
        RETRY,
        /** This message cannot be sent (a malformed request); do not retry. */
        FAILED,
        /** Our credentials were refused: stop and say so in the log. */
        AUTH
    }

    /** The outcome, with the provider's wait hint (or null) and a short status code for the log (never a token). */
    record Outcome(Result result, Duration retryAfter, String code) {

        public static Outcome accepted() {
            return new Outcome(Result.ACCEPTED, null, "200");
        }

        public static Outcome of(Result result, String code) {
            return new Outcome(result, null, code);
        }
    }

    Outcome send(Push push);

    /** For the start-up log. */
    String describe();
}
