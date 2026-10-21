package com.safesphere.backend.emergency.repository;

import com.safesphere.backend.emergency.entity.EmergencyLocation;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Locations are looked up by emergency id only. The service always checks first that the emergency
 * belongs to the caller (EmergencyEventRepository.findByIdAndUserId), so these methods are never
 * reached with someone else's emergency id.
 */
public interface EmergencyLocationRepository extends JpaRepository<EmergencyLocation, Long> {

    long countByEmergencyId(Long emergencyId);

    Optional<EmergencyLocation> findFirstByEmergencyIdOrderByRecordedAtDescIdDesc(Long emergencyId);

    /** Newest first; capped at 100 so the response stays small. */
    List<EmergencyLocation> findTop100ByEmergencyIdOrderByRecordedAtDescIdDesc(Long emergencyId);

    /** Which of these timestamps are already stored (a re-sent batch must not create duplicates). */
    @Query("select l.recordedAt from EmergencyLocation l "
            + "where l.emergencyId = :emergencyId and l.recordedAt in :recordedAt")
    List<LocalDateTime> findExistingRecordedAt(@Param("emergencyId") Long emergencyId,
                                               @Param("recordedAt") Collection<LocalDateTime> recordedAt);
}
