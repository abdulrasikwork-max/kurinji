package com.safesphere.backend.moderation.repository;

import com.safesphere.backend.moderation.entity.SocialStrike;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 13D: strikes (moderators only). */
public interface SocialStrikeRepository extends JpaRepository<SocialStrike, Long> {

    List<SocialStrike> findByUserId(long userId);

    @Modifying
    @Query("delete from SocialStrike s where s.decision = :decision")
    int deleteByDecision(@Param("decision") String decision);

    @Modifying
    @Query("delete from SocialStrike s where s.createdAt <= :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
