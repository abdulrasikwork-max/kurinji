package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.DailyState;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 17E: daily states. Read one person at a time; never listed, never counted. */
public interface DailyStateRepository extends JpaRepository<DailyState, Long> {

    /** The clean-up: every state of an earlier IST day (no history is kept). */
    @Modifying
    @Query("DELETE FROM DailyState d WHERE d.setOn < :today")
    int deleteBefore(@Param("today") LocalDate today);
}
