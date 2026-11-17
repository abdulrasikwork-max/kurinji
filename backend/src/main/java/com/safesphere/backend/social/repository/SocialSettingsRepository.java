package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.SocialSettings;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Phase N5: who may reply privately to a person's moments (no row = MY_PEOPLE). */
public interface SocialSettingsRepository extends JpaRepository<SocialSettings, Long> {

    List<SocialSettings> findByUserIdIn(Collection<Long> userIds);
}
