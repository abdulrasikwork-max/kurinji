package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.PostFacet;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 17G-1 (V29): Explore tags of posts. Always read for posts the caller has already shown to be visible. */
public interface PostFacetRepository extends JpaRepository<PostFacet, Long> {

    List<PostFacet> findByPostIdIn(Collection<Long> postIds);

    /** A deleted post loses its tags (MySQL cascades; the test database has no foreign keys, so this is explicit). */
    @Modifying
    @Query("DELETE FROM PostFacet f WHERE f.postId = :postId")
    int deleteAllOfPost(@Param("postId") long postId);
}
