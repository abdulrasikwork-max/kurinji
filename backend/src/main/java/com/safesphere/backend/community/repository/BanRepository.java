package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.Ban;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BanRepository extends JpaRepository<Ban, Long> {

    /** Bans in force on [rollKey] in [communityId], newest first. */
    @Query("select b from Ban b where b.communityId = :communityId and b.liftedAt is null and b.rollKey = :rollKey"
            + " order by b.createdAt desc")
    List<Ban> inForceOnRoll(@Param("communityId") long communityId, @Param("rollKey") String rollKey);

    /** Bans in force on [phoneKey] in [communityId], newest first. */
    @Query("select b from Ban b where b.communityId = :communityId and b.liftedAt is null and b.phoneKey = :phoneKey"
            + " order by b.createdAt desc")
    List<Ban> inForceOnPhone(@Param("communityId") long communityId, @Param("phoneKey") String phoneKey);

    @Query("select b from Ban b where b.communityId = :communityId order by b.createdAt desc, b.id desc")
    List<Ban> newestFirst(@Param("communityId") long communityId, Pageable page);
}
