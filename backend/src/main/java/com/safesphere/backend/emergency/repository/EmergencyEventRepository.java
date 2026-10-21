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

    /** Newest first; capped at 50 entries so the history can never grow into a huge response. */
    List<EmergencyEvent> findTop50ByUserIdOrderByStartedAtDescIdDesc(Long userId);
}
