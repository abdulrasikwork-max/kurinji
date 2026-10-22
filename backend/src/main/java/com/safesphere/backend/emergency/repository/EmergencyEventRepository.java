package com.safesphere.backend.emergency.repository;

import com.safesphere.backend.emergency.entity.EmergencyEvent;
import com.safesphere.backend.emergency.entity.EmergencyStatus;
import java.util.List;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Like the contact repository: every lookup includes the user id, so ownership cannot be skipped. */
public interface EmergencyEventRepository extends JpaRepository<EmergencyEvent, Long> {

    Optional<EmergencyEvent> findByIdAndUserId(Long id, Long userId);

    /**
     * Same lookup, but locks the row until the transaction ends (SELECT ... FOR UPDATE). Location uploads
     * use it so two batches for one emergency are stored one after the other: the per-emergency limit and
     * the duplicate check stay correct even when a phone sends two requests at once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmergencyEvent e where e.id = :id and e.userId = :userId")
    Optional<EmergencyEvent> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    boolean existsByUserIdAndStatus(Long userId, EmergencyStatus status);

    /** Phase 11: the caller's emergency with this phone reference (re-sent start requests return it). */
    Optional<EmergencyEvent> findByUserIdAndClientRef(Long userId, String clientRef);

    /** Phase 10A: when the person's most recent emergency ended (account deletion waits 24 h after it). */
    @Query("select max(e.endedAt) from EmergencyEvent e where e.userId = :userId")
    Optional<java.time.LocalDateTime> lastEndedAt(@Param("userId") Long userId);

    /**
     * Phase 11 C1: the same phone signed in again (it proved it held the old sign-in's refresh secret): its ACTIVE SOS
     * now belongs to the new sign-in, so ending it there needs no password (it is the same phone, not "another one").
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EmergencyEvent e set e.startedSession = :newSession where e.userId = :userId"
            + " and e.status = com.safesphere.backend.emergency.entity.EmergencyStatus.ACTIVE"
            + " and e.startedSession = :oldSession")
    int moveStartedSession(@Param("userId") Long userId, @Param("oldSession") String oldSession,
                           @Param("newSession") String newSession);

    /** Newest first; capped at 50 entries so the history can never grow into a huge response. */
    List<EmergencyEvent> findTop50ByUserIdOrderByStartedAtDescIdDesc(Long userId);
}
