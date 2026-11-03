package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.PlaceVisit;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Phase 17G-2 (U5, V30): "I went" and ratings. Every read of an individual row is by the OWNER's id; the only cross-person read
 * is [totals], which returns two numbers and no person.
 */
public interface PlaceVisitRepository extends JpaRepository<PlaceVisit, Long> {

    /** How many ratings count and their sum (the two inputs of RatingPolicy). Never a person. */
    record Totals(long raters, long sum) {
    }

    Optional<PlaceVisit> findByUserIdAndPlaceId(Long userId, Long placeId);

    List<PlaceVisit> findByPlaceId(Long placeId);

    /**
     * The ratings of [placeId] that count: from accounts that are enabled, not erased and past probation (created at or before
     * [cutoff]), so a handful of fresh throwaway accounts cannot create a rating.
     */
    @Query("SELECT new com.safesphere.backend.social.repository.PlaceVisitRepository$Totals(COUNT(v), COALESCE(SUM(v.rating), 0))"
            + " FROM PlaceVisit v, com.safesphere.backend.user.entity.User u"
            + " WHERE u.id = v.userId AND v.placeId = :placeId AND v.rating IS NOT NULL"
            + " AND u.enabled = true AND u.erasedAt IS NULL AND u.createdAt <= :cutoff")
    Totals totals(@Param("placeId") long placeId, @Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM PlaceVisit v WHERE v.userId = :userId")
    int deleteAllOfUser(@Param("userId") long userId);
}
