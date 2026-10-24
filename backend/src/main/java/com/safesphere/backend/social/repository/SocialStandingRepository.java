package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.SocialStanding;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 13D: one row per person with a pause in the social features (by a moderator); deleted once it has ended. */
public interface SocialStandingRepository extends JpaRepository<SocialStanding, Long> {

    @Modifying
    @Query("delete from SocialStanding s where s.restrictedUntil <= :now")
    int deleteEnded(@Param("now") LocalDateTime now);
}
