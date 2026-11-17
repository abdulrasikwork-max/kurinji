package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.RepliesOpened;
import org.springframework.data.jpa.repository.JpaRepository;

/** Phase N5: when a person last opened Replies (their own marker, N-93). */
public interface RepliesOpenedRepository extends JpaRepository<RepliesOpened, Long> {
}
