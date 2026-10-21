package com.safesphere.backend.moderation.repository;

import com.safesphere.backend.moderation.entity.ModerationAction;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The audit log. Only inserts and the retention delete; no update method exists on purpose. */
public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    /** The newest restriction about this person (for their own standing view and their appeal). */
    Optional<ModerationAction> findFirstBySubjectUserIdAndActionOrderByOccurredAtDescIdDesc(Long subjectUserId,
                                                                                          ActionType action);

    List<ModerationAction> findAllByOrderByOccurredAtDescIdDesc(Pageable page);

    /** Phase 9B (gate G1): how many different moderators took one of [actions] since [since]. A count, no one named. */
    @Query("select count(distinct a.actorId) from ModerationAction a where a.action in :actions and a.occurredAt >= :since")
    long countActorsSince(@Param("actions") Collection<ActionType> actions, @Param("since") LocalDateTime since);

    @Modifying
    @Query("delete from ModerationAction a where a.occurredAt <= :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
