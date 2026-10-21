package com.safesphere.backend.evidence.repository;

import com.safesphere.backend.evidence.entity.RecoveryFailure;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryFailureRepository extends JpaRepository<RecoveryFailure, Long> {

    long countByUserIdAndOccurredAtAfter(Long userId, LocalDateTime since);
}
