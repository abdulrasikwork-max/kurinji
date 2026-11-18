package com.safesphere.backend.circlealert.repository;

import com.safesphere.backend.circlealert.entity.AlertSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/** N6: one row per person (the id IS the user id, always from the JWT). */
public interface AlertSettingRepository extends JpaRepository<AlertSetting, Long> {
}
