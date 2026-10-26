package com.safesphere.backend.auth.repository;

import com.safesphere.backend.auth.entity.EmailVerificationToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * Consumes the token: 1 for exactly one caller, 0 for everyone else (already used, replaced or expired). Two
     * simultaneous verifications of one link therefore cannot both succeed: the row lock serialises the deletes.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM EmailVerificationToken t WHERE t.userId = :userId AND t.tokenHash = :hash AND t.expiresAt > :now")
    int consume(@Param("userId") long userId, @Param("hash") String hash, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM EmailVerificationToken t WHERE t.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
