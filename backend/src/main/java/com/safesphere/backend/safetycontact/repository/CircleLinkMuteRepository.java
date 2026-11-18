package com.safesphere.backend.safetycontact.repository;

import com.safesphere.backend.safetycontact.entity.CircleLinkMute;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CircleLinkMuteRepository extends JpaRepository<CircleLinkMute, CircleLinkMute.Key> {
}
