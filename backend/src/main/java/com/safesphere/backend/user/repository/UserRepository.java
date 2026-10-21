package com.safesphere.backend.user.repository;

import com.safesphere.backend.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data generates the implementation. The derived query methods become parameterised SQL,
 * so user input is never concatenated into a query (no SQL injection).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Locks the user row until the transaction ends (SELECT ... FOR UPDATE). The evidence custody ledger uses it so
     * that two appends for one person get consecutive sequence numbers and an unbroken hash chain.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /** Phase 10A.3: accounts whose deletion grace period is over, oldest first (the deletion job). */
    @Query("select u.id from User u where u.deletionDueAt is not null and u.deletionDueAt <= :now "
            + "order by u.deletionDueAt asc")
    java.util.List<Long> findIdsDueForDeletion(@Param("now") java.time.LocalDateTime now,
                                               org.springframework.data.domain.Pageable page);
}
