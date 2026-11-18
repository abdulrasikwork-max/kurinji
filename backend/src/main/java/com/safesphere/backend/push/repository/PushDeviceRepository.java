package com.safesphere.backend.push.repository;

import com.safesphere.backend.push.entity.PushDevice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushDeviceRepository extends JpaRepository<PushDevice, Long> {

    Optional<PushDevice> findBySessionId(Long sessionId);

    Optional<PushDevice> findByTokenHash(String tokenHash);

    /** The phones of a person whose sign-in is still running (not revoked, not expired). */
    @Query("SELECT d FROM PushDevice d, DeviceSession s WHERE s.id = d.sessionId AND d.userId = :userId"
            + " AND s.revokedAt IS NULL AND s.expiresAt > :now")
    List<PushDevice> findActiveByUserId(@Param("userId") long userId, @Param("now") LocalDateTime now);

    /** Is the device's sign-in still running? (Checked again at send time.) */
    @Query("SELECT COUNT(s) > 0 FROM PushDevice d, DeviceSession s WHERE d.id = :deviceId AND s.id = d.sessionId"
            + " AND s.revokedAt IS NULL AND s.expiresAt > :now")
    boolean isActive(@Param("deviceId") long deviceId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM PushDevice d WHERE d.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") long sessionId);

    /** Clean-up: devices of ended sign-ins, of sessions that no longer exist, and tokens nobody refreshed in time. */
    @Query("SELECT d.id FROM PushDevice d WHERE d.updatedAt < :staleBefore OR NOT EXISTS (SELECT s FROM DeviceSession s"
            + " WHERE s.id = d.sessionId AND s.revokedAt IS NULL AND s.expiresAt > :now)")
    List<Long> findEnded(@Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);
}
