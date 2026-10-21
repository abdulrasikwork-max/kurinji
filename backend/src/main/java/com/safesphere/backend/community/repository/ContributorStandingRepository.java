package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.ContributorStanding;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Private standing rows (Phase 7). Never exposed through any endpoint. */
public interface ContributorStandingRepository extends JpaRepository<ContributorStanding, Long> {

    List<ContributorStanding> findByDecayedAtLessThanEqual(LocalDateTime cutoff);
}
