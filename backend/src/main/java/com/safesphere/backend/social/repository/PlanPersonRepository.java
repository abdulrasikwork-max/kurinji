package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.PlanPerson;
import com.safesphere.backend.social.entity.ResponseEnums.PlanState;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase N5: the people and a Who's in? (N-90). */
public interface PlanPersonRepository extends JpaRepository<PlanPerson, Long> {

    Optional<PlanPerson> findByMomentIdAndUserId(long momentId, long userId);

    Optional<PlanPerson> findByPublicIdAndMomentId(String publicId, long momentId);

    /** Everyone on one plan, in the order they asked. */
    List<PlanPerson> findByMomentIdOrderByAskedAtAscIdAsc(long momentId);

    List<PlanPerson> findByMomentIdAndStateOrderByAnsweredAtAscIdAsc(long momentId, PlanState state);

    /** [userId]'s own rows on a list of moments (their state on a page of Today). */
    List<PlanPerson> findByUserIdAndMomentIdIn(long userId, Collection<Long> momentIds);

    /** All rows on a list of moments (the author's lines for a page). */
    List<PlanPerson> findByMomentIdIn(Collection<Long> momentIds);

    /** [userId]'s rows in a state (their plans in Replies). */
    List<PlanPerson> findByUserIdAndState(long userId, PlanState state);

    boolean existsByMomentIdAndUserIdAndState(long momentId, long userId, PlanState state);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PlanPerson p WHERE p.momentId IN :momentIds")
    int deleteByMomentIdIn(@Param("momentIds") Collection<Long> momentIds);
}
