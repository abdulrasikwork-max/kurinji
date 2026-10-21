package com.safesphere.backend.journey.repository;

import com.safesphere.backend.journey.entity.JourneyStatus;
import com.safesphere.backend.journey.entity.SafeJourney;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every lookup includes the user id, so ownership cannot be skipped. */
public interface SafeJourneyRepository extends JpaRepository<SafeJourney, Long> {

    Optional<SafeJourney> findByIdAndUserId(Long id, Long userId);

    /** Locks the row (SELECT ... FOR UPDATE): a check-in and an escalation for one journey run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from SafeJourney j where j.id = :id and j.userId = :userId")
    Optional<SafeJourney> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from SafeJourney j where j.userId = :userId and j.status = :status")
    Optional<SafeJourney> findByUserIdAndStatusForUpdate(@Param("userId") Long userId, @Param("status") JourneyStatus status);

    boolean existsByUserIdAndStatus(Long userId, JourneyStatus status);

    /** Newest first; capped so the history stays small. */
    List<SafeJourney> findTop20ByUserIdOrderByStartedAtDescIdDesc(Long userId);
}
