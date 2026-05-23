package com.tokengateway.repository;

import com.tokengateway.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    Optional<IdempotencyKey> findByIdempotencyKeyAndUserId(String idempotencyKey, String userId);

    /** Periodic cleanup of expired records */
    @Modifying
    @Transactional
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpiredKeys(Instant now);
}
