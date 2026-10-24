package com.safesphere.backend.moderation.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Case-scoped pseudonyms for the people in a moderation case (Phase 7). A moderator needs to tell participants apart
 * ("this reporter and these two confirmers act together") and to act on one of them, but has no need to know WHO they
 * are or to recognise the same person in another case. So each participant is shown as
 *
 *     ref = first 12 hex characters of HMAC-SHA256(random key, caseId ":" userId)
 *
 * The same person has a different ref in every case (no cross-case profile), and the key is random per server start
 * and never stored or logged, so refs cannot be reversed or precomputed from the database. A ref is turned back into
 * a person only by recomputing it for that case's own participants.
 */
@Component
public class CaseRefs {

    private final byte[] key = new byte[32];

    public CaseRefs() {
        new SecureRandom().nextBytes(key);
    }

    public String ref(long caseId, long userId) {
        return mac(caseId + ":" + userId);
    }

    /**
     * Phase 13D: refs of a content case. Content cases are numbered separately from signal cases, so their input has its
     * own prefix: the same person in signal case 5 and content case 5 still gets two unrelated refs.
     */
    public String contentRef(long caseId, long userId) {
        return mac("content:" + caseId + ":" + userId);
    }

    private String mac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is always available", e);
        }
    }
}
