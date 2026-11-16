package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.CommunityAction;
import com.safesphere.backend.community.entity.CommunityEnums.ActionKind;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityActionRepository extends JpaRepository<CommunityAction, Long> {

    /** The newest log rows about [userId] of the given kinds (for what the person is shown). */
    @Query("select a from CommunityAction a where a.subjectUserId = :userId and a.action in :kinds"
            + " order by a.createdAt desc, a.id desc")
    List<CommunityAction> aboutPerson(@Param("userId") long userId, @Param("kinds") Collection<ActionKind> kinds,
                                      Pageable page);

    /** How many approvals [actorId] made since [since] (the daily cap). */
    long countByActorIdAndActionAndCreatedAtAfter(long actorId, ActionKind action, LocalDateTime since);

    @Query("select a from CommunityAction a where a.communityId = :communityId order by a.createdAt desc, a.id desc")
    List<CommunityAction> newestFirst(@Param("communityId") long communityId, Pageable page);

    boolean existsByActorId(long actorId);

    /** AccountEraser: the log rows about a person (the database cascades too; H2 tests have no cascade). */
    @Modifying
    @Query("delete from CommunityAction a where a.subjectUserId = :userId")
    int deleteAboutPerson(@Param("userId") long userId);
}
