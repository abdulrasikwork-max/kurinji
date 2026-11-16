package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.NumberRelink;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NumberRelinkRepository extends JpaRepository<NumberRelink, Long> {

    Optional<NumberRelink> findByPhoneE164(String phoneE164);

    boolean existsByCodeHash(String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from NumberRelink r where r.codeHash = :codeHash")
    Optional<NumberRelink> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    @Modifying
    @Query("delete from NumberRelink r where r.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
