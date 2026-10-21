package com.safesphere.backend.evidence.repository;

import com.safesphere.backend.evidence.entity.CustodyAction;
import com.safesphere.backend.evidence.entity.CustodyEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: the application only ever inserts and reads custody rows. */
public interface CustodyEventRepository extends JpaRepository<CustodyEvent, Long> {

    Optional<CustodyEvent> findFirstByUserIdOrderBySequenceNoDesc(Long userId);

    List<CustodyEvent> findByUserIdOrderBySequenceNoAsc(Long userId);

    /** Recent entries of one kind (e.g. refused deletions in the lockout window). */
    long countByUserIdAndActionAndOccurredAtAfter(Long userId, CustodyAction action, LocalDateTime after);
}
