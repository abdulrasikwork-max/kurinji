package com.safesphere.backend.push.repository;

import com.safesphere.backend.push.entity.PushSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSettingRepository extends JpaRepository<PushSetting, Long> {
}
