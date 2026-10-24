package com.safesphere.backend.moderation.repository;

import com.safesphere.backend.moderation.entity.ModerationAppeal;
import com.safesphere.backend.moderation.entity.ModerationEnums.ActionType;
import com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationAppealRepository extends JpaRepository<ModerationAppeal, Long> {

    Optional<ModerationAppeal> findByPublicId(String publicId);

    Optional<ModerationAppeal> findByActionId(Long actionId);

    List<ModerationAppeal> findByActionIdIn(Collection<Long> actionIds);

    /**
     * Appeals in [status] about decisions of the given kinds, oldest first. Phase 7 lists RESTRICT appeals only; Phase 13D
     * lists the appeals about social decisions (the shared table holds both, M11).
     */
    @Query("select p from ModerationAppeal p where p.status = :status and p.actionId in "
            + "(select a.id from ModerationAction a where a.action in :actions) order by p.createdAt asc, p.id asc")
    List<ModerationAppeal> findByStatusAndActionIn(@Param("status") AppealStatus status,
                                                   @Param("actions") Collection<ActionType> actions, Pageable page);
}
