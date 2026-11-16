package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.MomentMedia;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase N4: the photo rows of moments (the files are in MediaStorage). */
public interface MomentMediaRepository extends JpaRepository<MomentMedia, Long> {

    List<MomentMedia> findByMomentIdIn(Collection<Long> momentIds);

    /** Locked, so the clean-up and an attach of the same upload never cross. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MomentMedia m WHERE m.momentId IS NULL AND m.createdAt < :before")
    List<MomentMedia> findUnattachedBefore(@Param("before") LocalDateTime before);

    /** Locked, so an attach, a removal and the clean-up of the same upload never cross. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MomentMedia m WHERE m.publicId = :publicId")
    List<MomentMedia> findForAttach(@Param("publicId") String publicId);

    List<MomentMedia> findByStorageKeyIn(Collection<String> storageKeys);
}
