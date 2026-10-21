package com.safesphere.backend.moderation.repository;

import com.safesphere.backend.moderation.entity.ModerationAppeal;
import com.safesphere.backend.moderation.entity.ModerationEnums.AppealStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationAppealRepository extends JpaRepository<ModerationAppeal, Long> {

    Optional<ModerationAppeal> findByPublicId(String publicId);

    Optional<ModerationAppeal> findByActionId(Long actionId);

    List<ModerationAppeal> findByStatusOrderByCreatedAtAsc(AppealStatus status, Pageable page);
}
