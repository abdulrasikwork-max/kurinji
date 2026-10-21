package com.safesphere.backend.escalation.repository;

import com.safesphere.backend.escalation.entity.EscalationEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Looked up by journey or emergency id only; services check first that the journey/emergency belongs to
 * the caller. Read by the Emergency Context Packet (Phase 4D).
 */
public interface EscalationEventRepository extends JpaRepository<EscalationEvent, Long> {

    List<EscalationEvent> findByJourneyIdOrderByCreatedAtAscIdAsc(Long journeyId);

    List<EscalationEvent> findByEmergencyIdOrderByCreatedAtAscIdAsc(Long emergencyId);

    boolean existsByEmergencyIdAndLevel(Long emergencyId, Integer level);
}
