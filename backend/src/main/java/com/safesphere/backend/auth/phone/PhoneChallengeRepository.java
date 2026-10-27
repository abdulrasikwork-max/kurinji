package com.safesphere.backend.auth.phone;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PhoneChallengeRepository extends JpaRepository<PhoneChallenge, Long> {

    /** Locks the row until the transaction ends: two simultaneous tries of one challenge are counted one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PhoneChallenge c where c.tokenHash = :hash")
    Optional<PhoneChallenge> findByTokenHashForUpdate(@Param("hash") String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PhoneChallenge c WHERE c.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
