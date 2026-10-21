package com.safesphere.backend.circlealert.repository;

import com.safesphere.backend.circlealert.entity.CircleAlert;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Looked up by emergency / journey id or client reference only; the service checks first that the emergency or
 * journey belongs to the caller.
 */
public interface CircleAlertRepository extends JpaRepository<CircleAlert, Long> {

    List<CircleAlert> findByClientRefIn(Collection<String> clientRefs);

    List<CircleAlert> findByEmergencyIdOrderByOccurredAtAscIdAsc(Long emergencyId);

    List<CircleAlert> findByJourneyIdOrderByOccurredAtAscIdAsc(Long journeyId);

    long countByEmergencyId(Long emergencyId);

    long countByJourneyId(Long journeyId);
}
