package com.safesphere.backend.auth.repository;

import com.safesphere.backend.auth.entity.EmailVerificationToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V25 e-mail links. Phase 17B took e-mail verification out of the sign-up flow (no link is issued any more); the table
 * stays (applied migrations are never edited) and the clean-up job removes the rows left from before.
 */
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM EmailVerificationToken t WHERE t.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
