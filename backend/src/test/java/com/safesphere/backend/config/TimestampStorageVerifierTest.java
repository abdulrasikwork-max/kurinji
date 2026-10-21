package com.safesphere.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

/** The start-up timestamp check: passes on an unchanged value, stops the backend on a shifted one. */
class TimestampStorageVerifierTest {

    private static final String UNCHANGED = "2026-01-15 12:34:56.123456";
    private static final String IST_SHIFTED = "2026-01-15 07:04:56.123456"; // 5 h 30 m behind: the original bug

    @Test
    void passesWhenTheDatabaseReceivesTheUtcValueUnchanged() {
        TimestampStorageVerifier verifier = verifierThatReceives(UNCHANGED, true);

        assertDoesNotThrow(() -> verifier.run(new DefaultApplicationArguments()));
    }

    @Test
    void refusesToStartWhenTheValueArrivesShifted() {
        TimestampStorageVerifier verifier = verifierThatReceives(IST_SHIFTED, true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> verifier.run(new DefaultApplicationArguments()));

        assertTrue(failure.getMessage().contains(IST_SHIFTED), failure.getMessage());
    }

    @Test
    void doesNothingWhenSwitchedOff() {
        EntityManager entityManager = mock(EntityManager.class);
        TimestampStorageVerifier verifier = new TimestampStorageVerifier();
        ReflectionTestUtils.setField(verifier, "entityManager", entityManager);
        ReflectionTestUtils.setField(verifier, "enabled", false);

        assertDoesNotThrow(() -> verifier.run(new DefaultApplicationArguments()));

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    void probesWithTheUtcValueItExpects() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(UNCHANGED);
        TimestampStorageVerifier verifier = new TimestampStorageVerifier();
        ReflectionTestUtils.setField(verifier, "entityManager", entityManager);
        ReflectionTestUtils.setField(verifier, "enabled", true);

        verifier.run(new DefaultApplicationArguments());

        verify(query).setParameter("probe", LocalDateTime.of(2026, 1, 15, 12, 34, 56, 123_456_000));
    }

    private static TimestampStorageVerifier verifierThatReceives(String received, boolean enabled) {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(received);
        TimestampStorageVerifier verifier = new TimestampStorageVerifier();
        ReflectionTestUtils.setField(verifier, "entityManager", entityManager);
        ReflectionTestUtils.setField(verifier, "enabled", enabled);
        return verifier;
    }
}
