package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.Membership;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByUserId(long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.userId = :userId")
    Optional<Membership> findByUserIdForUpdate(@Param("userId") long userId);

    Optional<Membership> findByCommunityIdAndRollKey(long communityId, String rollKey);

    /** The ids among [userIds] with an active membership of [communityId] at [now] (one query for a list). */
    @Query("select m.userId from Membership m where m.communityId = :communityId and m.userId in :userIds"
            + " and m.endedAt is null and m.endsAt > :now")
    List<Long> activeAmong(@Param("communityId") long communityId, @Param("userIds") Collection<Long> userIds,
                           @Param("now") LocalDateTime now);

    /** Memberships whose graduation end has passed but are not marked ended yet (the hourly job). */
    @Query("select m from Membership m where m.endedAt is null and m.endsAt <= :now order by m.endsAt")
    List<Membership> pastTheirEnd(@Param("now") LocalDateTime now, Pageable page);

    @Query("select m from Membership m where m.communityId = :communityId and m.verifier = true"
            + " order by m.verifierSince")
    List<Membership> verifiersOf(@Param("communityId") long communityId);
}
