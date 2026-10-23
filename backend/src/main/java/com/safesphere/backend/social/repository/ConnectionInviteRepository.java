package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.ConnectionInvite;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectionInviteRepository extends JpaRepository<ConnectionInvite, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ConnectionInvite i where i.codeHash = :codeHash")
    Optional<ConnectionInvite> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    Optional<ConnectionInvite> findByPublicIdAndOwnerId(String publicId, Long ownerId);

    @Query("select i from ConnectionInvite i where i.ownerId = :ownerId and i.usedAt is null and i.expiresAt > :now"
            + " order by i.createdAt desc")
    List<ConnectionInvite> findActive(@Param("ownerId") Long ownerId, @Param("now") LocalDateTime now);
}
