package com.safesphere.backend.journey.repository;

import com.safesphere.backend.journey.entity.JourneyEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Looked up by journey id only, after the service has checked the journey belongs to the caller. */
public interface JourneyEventRepository extends JpaRepository<JourneyEvent, Long> {

    List<JourneyEvent> findByJourneyIdOrderByOccurredAtAscIdAsc(Long journeyId);
}
