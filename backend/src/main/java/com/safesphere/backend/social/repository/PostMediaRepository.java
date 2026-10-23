package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.PostMedia;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 13B: photo rows (the files are in MediaStorage). */
public interface PostMediaRepository extends JpaRepository<PostMedia, Long> {

    Optional<PostMedia> findByPublicId(String publicId);

    /** Locked, so two posts can never attach the same upload. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM PostMedia m WHERE m.publicId IN :ids")
    List<PostMedia> findForAttach(@Param("ids") Collection<String> publicIds);

    List<PostMedia> findByPostIdOrderBySortOrderAsc(long postId);

    List<PostMedia> findByPostIdIn(Collection<Long> postIds);

    /** Locked like [findForAttach], so the clean-up and an attach of the same upload never cross. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM PostMedia m WHERE m.postId IS NULL AND m.createdAt < :before")
    List<PostMedia> findUnattachedBefore(@Param("before") LocalDateTime before);

    List<PostMedia> findByStorageKeyIn(Collection<String> storageKeys);
}
