package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.Community;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityRepository extends JpaRepository<Community, Long> {

    Optional<Community> findByCode(String code);
}
