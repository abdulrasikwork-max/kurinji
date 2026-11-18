package com.safesphere.backend.circlealert.repository;

import com.safesphere.backend.circlealert.entity.AlertStop;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** N6: the stop list, looked up by a number's HMAC only. */
public interface AlertStopRepository extends JpaRepository<AlertStop, Long> {

    boolean existsByNumberKey(String numberKey);

    Optional<AlertStop> findByNumberKey(String numberKey);
}
