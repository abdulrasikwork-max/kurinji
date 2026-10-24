package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.PostRevision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Phase 13D (Q7): earlier texts of a post under review, for its moderator only. */
public interface PostRevisionRepository extends JpaRepository<PostRevision, Long> {

    List<PostRevision> findByPostIdOrderByReplacedAtAscIdAsc(long postId);

    @Modifying
    @Query("DELETE FROM PostRevision r WHERE r.postId = :postId")
    int deleteByPost(@Param("postId") long postId);
}
