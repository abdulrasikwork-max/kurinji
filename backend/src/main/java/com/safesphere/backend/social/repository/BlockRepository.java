package com.safesphere.backend.social.repository;

import com.safesphere.backend.social.entity.Block;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockRepository extends JpaRepository<Block, Long> {

    Optional<Block> findByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    List<Block> findByBlockerIdOrderByCreatedAtDesc(Long blockerId);

    /** True when either of the two blocked the other. */
    @Query("select count(b) > 0 from Block b where (b.blockerId = :a and b.blockedId = :b)"
            + " or (b.blockerId = :b and b.blockedId = :a)")
    boolean existsEitherWay(@Param("a") Long a, @Param("b") Long b);
}
