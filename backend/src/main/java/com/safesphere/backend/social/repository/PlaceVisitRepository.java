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
 * is [raters] (Phase 18A-1), which PlaceRatings alone calls to compute the ONE public text: its rows never leave the server.
 */
public interface PlaceVisitRepository extends JpaRepository<PlaceVisit, Long> {

    /**
     * Phase 18A-1: one counted rating with the two facts RatingPolicy weighs it by. Read ONLY by PlaceRatings, to compute the one
     * public text; never returned by any endpoint.
     */
    record RaterRow(Long userId, Integer rating, LocalDateTime accountCreatedAt, Long placesRated) {
    }

    Optional<PlaceVisit> findByUserIdAndPlaceId(Long userId, Long placeId);

    List<PlaceVisit> findByPlaceId(Long placeId);

    /**
     * The ratings of [placeId] that count: from accounts that are enabled, not erased and past probation (created at or before
     * [cutoff]), so a handful of fresh throwaway accounts cannot create a rating. With each: when the account was made and how
     * many different places it has rated.
     */
    @Query("SELECT new com.safesphere.backend.social.repository.PlaceVisitRepository$RaterRow(v.userId, v.rating, u.createdAt,"
            + " (SELECT COUNT(o) FROM PlaceVisit o WHERE o.userId = v.userId AND o.rating IS NOT NULL))"
            + " FROM PlaceVisit v, com.safesphere.backend.user.entity.User u"
            + " WHERE u.id = v.userId AND v.placeId = :placeId AND v.rating IS NOT NULL"
            + " AND u.enabled = true AND u.erasedAt IS NULL AND u.createdAt <= :cutoff")
    List<RaterRow> raters(@Param("placeId") long placeId, @Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("DELETE FROM PlaceVisit v WHERE v.userId = :userId")
    int deleteAllOfUser(@Param("userId") long userId);
}
