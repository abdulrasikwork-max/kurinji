package com.safesphere.backend.evidence.repository;

import com.safesphere.backend.evidence.entity.RecoveryEnvelope;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecoveryEnvelopeRepository extends JpaRepository<RecoveryEnvelope, Long> {

    Optional<RecoveryEnvelope> findByUserId(Long userId);
}
