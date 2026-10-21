package com.safesphere.backend.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.safesphere.backend.evidence.entity.CustodyAction;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** The custody hash is deterministic and covers every field (the ledger format must never change silently). */
class CustodyLedgerHashTest {

    private static final String PREVIOUS = "0".repeat(64);
    private static final String SHA = "a".repeat(64);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 20, 12, 0, 0, 123_456_000);

    @Test
    void sameInput_sameHash_andAKnownValue() throws Exception {
        String hash = hash(PREVIOUS, 1, 1, 7, CustodyAction.SEALED, SHA, AT);
        assertEquals(hash, hash(PREVIOUS, 1, 1, 7, CustodyAction.SEALED, SHA, AT));
        assertEquals(64, hash.length());
        // Pinned value: changing the field order or formats breaks every stored chain, so it must fail here first.
        assertEquals(com.safesphere.backend.evidence.service.Sha256.ofText(
                "SSCL1|" + PREVIOUS + "|1|1|7|SEALED|" + SHA + "|2026-10-20T12:00:00.123456Z"), hash);
    }

    @Test
    void everyFieldChangesTheHash() throws Exception {
        String base = hash(PREVIOUS, 1, 1, 7, CustodyAction.SEALED, SHA, AT);
        assertNotEquals(base, hash("1".repeat(64), 1, 1, 7, CustodyAction.SEALED, SHA, AT));
        assertNotEquals(base, hash(PREVIOUS, 2, 1, 7, CustodyAction.SEALED, SHA, AT));
        assertNotEquals(base, hash(PREVIOUS, 1, 2, 7, CustodyAction.SEALED, SHA, AT));
        assertNotEquals(base, hash(PREVIOUS, 1, 1, 8, CustodyAction.SEALED, SHA, AT));
        assertNotEquals(base, hash(PREVIOUS, 1, 1, 7, CustodyAction.DELETED, SHA, AT));
        assertNotEquals(base, hash(PREVIOUS, 1, 1, 7, CustodyAction.SEALED, "b".repeat(64), AT));
        assertNotEquals(base, hash(PREVIOUS, 1, 1, 7, CustodyAction.SEALED, SHA, AT.plusNanos(1000)));
    }

    private static String hash(String previous, long user, long sequence, long evidence, CustodyAction action,
                               String sha, LocalDateTime at) throws Exception {
        Method method = Class.forName("com.safesphere.backend.evidence.service.CustodyLedger").getDeclaredMethod("hashOf",
                String.class, long.class, long.class, long.class, CustodyAction.class, String.class, LocalDateTime.class);
        method.setAccessible(true);
        return (String) method.invoke(null, previous, user, sequence, evidence, action, sha, at);
    }
}
