package com.safesphere.backend.auth.repository;

import com.safesphere.backend.auth.entity.DeviceSession;
import com.safesphere.backend.auth.entity.SessionRevocation;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 11 C1: device sessions. Every query is by session id or by the owner's user id. */
public interface DeviceSessionRepository extends JpaRepository<DeviceSession, Long> {

    Optional<DeviceSession> findBySessionId(String sessionId);

    /** Locks the row until the transaction ends: two refreshes of one session run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DeviceSession s where s.sessionId = :sessionId")
    Optional<DeviceSession> findBySessionIdForUpdate(@Param("sessionId") String sessionId);

    /** Per-request check (ActiveAccountCheck): is this sign-in known and ended? */
    boolean existsBySessionIdAndRevokedAtIsNotNull(String sessionId);

    /** Ends every live session of [userId] except [keepSessionId] (null = all of them). Returns how many. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DeviceSession s set s.revokedAt = :now, s.revokedReason = :reason"
            + " where s.userId = :userId and s.revokedAt is null"
            + " and (:keepSessionId is null or s.sessionId <> :keepSessionId)")
    int revokeAllExcept(@Param("userId") Long userId, @Param("keepSessionId") String keepSessionId,
                        @Param("reason") SessionRevocation reason, @Param("now") LocalDateTime now);

    /** Clean-up: sessions revoked or expired before [cutoff] (their access tokens are long expired). */
    @Modifying
    @Query("delete from DeviceSession s where (s.revokedAt is not null and s.revokedAt < :cutoff)"
            + " or s.expiresAt < :cutoff")
    int deleteEndedBefore(@Param("cutoff") LocalDateTime cutoff);
}
