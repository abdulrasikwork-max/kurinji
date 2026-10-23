package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Mute;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MuteRepository extends JpaRepository<Mute, Long> {

    Optional<Mute> findByMuterIdAndMutedId(Long muterId, Long mutedId);

    List<Mute> findByMuterIdOrderByCreatedAtDesc(Long muterId);
}
