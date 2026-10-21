package com.safesphere.backend.safetycontact.repository;

import com.safesphere.backend.safetycontact.entity.SafetyContact;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every query that returns data is filtered by userId, so there is no method that can load
 * someone else's contact by id alone.
 */
public interface SafetyContactRepository extends JpaRepository<SafetyContact, Long> {

    /** Primary first, then by priority (1 = first), then alphabetical. */
    List<SafetyContact> findByUserIdOrderByPrimaryContactDescPriorityAscNameAsc(Long userId);

    Optional<SafetyContact> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    boolean existsByUserIdAndPhoneNumber(Long userId, String phoneNumber);

    boolean existsByUserIdAndPhoneNumberAndIdNot(Long userId, String phoneNumber, Long id);

    /** Un-marks the user's current primary contact (used before a new one is saved). */
    @Modifying(flushAutomatically = true)
    @Query("update SafetyContact c set c.primaryContact = false where c.userId = :userId and c.primaryContact = true")
    int clearPrimary(@Param("userId") Long userId);

    /** Same, but leaves one contact alone (the one being edited). */
    @Modifying(flushAutomatically = true)
    @Query("update SafetyContact c set c.primaryContact = false "
            + "where c.userId = :userId and c.primaryContact = true and c.id <> :keepId")
    int clearPrimaryExcept(@Param("userId") Long userId, @Param("keepId") Long keepId);
}
