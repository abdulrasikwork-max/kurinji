package com.safesphere.backend.safetycontact.repository;

import com.safesphere.backend.safetycontact.entity.CircleLink;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CircleLinkRepository extends JpaRepository<CircleLink, Long> {

    Optional<CircleLink> findByContactId(Long contactId);

    Optional<CircleLink> findByPublicIdAndMemberUserId(String publicId, Long memberUserId);

    List<CircleLink> findByMemberUserIdAndStatusOrderByCreatedAtDesc(Long memberUserId, CircleLink.Status status);

    List<CircleLink> findByOwnerUserIdAndStatus(Long ownerUserId, CircleLink.Status status);

    long countByOwnerUserIdAndCreatedAtAfter(Long ownerUserId, LocalDateTime after);

    @Modifying
    @Query("DELETE FROM CircleLink l WHERE l.contactId = :contactId")
    int deleteByContactId(@Param("contactId") long contactId);
}
