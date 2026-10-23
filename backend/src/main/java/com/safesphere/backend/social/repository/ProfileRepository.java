package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Profile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<Profile, Long> {

    Optional<Profile> findByUserId(Long userId);

    Optional<Profile> findByHandle(String handle);

    Optional<Profile> findByPreviousHandle(String previousHandle);

    Optional<Profile> findByPublicId(String publicId);

    List<Profile> findByUserIdIn(Collection<Long> userIds);
}
