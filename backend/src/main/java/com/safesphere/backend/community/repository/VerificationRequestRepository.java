package com.safesphere.backend.community.repository;

import com.safesphere.backend.community.entity.CommunityEnums.Method;
import com.safesphere.backend.community.entity.VerificationRequest;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, Long> {

    Optional<VerificationRequest> findByUserId(long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VerificationRequest r where r.userId = :userId")
    Optional<VerificationRequest> findByUserIdForUpdate(@Param("userId") long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VerificationRequest r where r.publicId = :publicId")
    Optional<VerificationRequest> findByPublicIdForUpdate(@Param("publicId") String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from VerificationRequest r where r.codeHash = :codeHash")
    Optional<VerificationRequest> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    boolean existsByCodeHash(String codeHash);

    Optional<VerificationRequest> findByPublicId(String publicId);

    /** The admin queue: photo requests with a photo, oldest first, not expired. */
    @Query("select r from VerificationRequest r where r.method = :method and r.photoUploadedAt is not null"
            + " and r.expiresAt > :now order by r.photoUploadedAt")
    List<VerificationRequest> photoQueue(@Param("method") Method method, @Param("now") LocalDateTime now,
                                         Pageable page);

    @Query("select r from VerificationRequest r where r.expiresAt <= :now order by r.expiresAt")
    List<VerificationRequest> expired(@Param("now") LocalDateTime now, Pageable page);

    /** Every photo file name still referenced (the sweep deletes any other file). */
    @Query("select r.photoKey from VerificationRequest r where r.photoKey is not null")
    List<String> photoKeys();
}
