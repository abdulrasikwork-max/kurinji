package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Profile;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

    Optional<Profile> findByUserId(Long userId);

    Optional<Profile> findByHandle(String handle);

    Optional<Profile> findByPreviousHandle(String previousHandle);

    Optional<Profile> findByPublicId(String publicId);

    List<Profile> findByUserIdIn(Collection<Long> userIds);

    /** Phase 14: locks the row, so that reports about one person are handled one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Profile p WHERE p.id = :id")
    Optional<Profile> lockById(@Param("id") long id);
}
