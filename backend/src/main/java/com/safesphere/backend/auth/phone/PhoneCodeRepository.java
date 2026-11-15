package com.safesphere.backend.auth.phone;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase N2 (V33). Every lookup that may count a wrong code or consume a ticket locks the row first. */
public interface PhoneCodeRepository extends JpaRepository<PhoneCode, Long> {

    Optional<PhoneCode> findByPurposeAndPhoneE164(PhoneCode.Purpose purpose, String phoneE164);

    /** Locks the row until the transaction ends: two simultaneous tries of one challenge are counted one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PhoneCode c where c.tokenHash = :hash")
    Optional<PhoneCode> findByTokenHashForUpdate(@Param("hash") String hash);

    /** Locks the row: a ticket can be used once, even by two simultaneous sign-ups. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PhoneCode c where c.ticketHash = :hash")
    Optional<PhoneCode> findByTicketHashForUpdate(@Param("hash") String hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PhoneCode c WHERE c.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
