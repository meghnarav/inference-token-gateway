package com.tokengateway.repository;

import com.tokengateway.model.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    Optional<UsageEvent> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);

    @Query("""
        SELECT COALESCE(SUM(e.tokensConsumed), 0)
        FROM UsageEvent e
        WHERE e.userId = :userId
          AND e.createdAt >= :since
        """)
    long sumTokensByUserSince(@Param("userId") String userId, @Param("since") Instant since);

    @Query("""
        SELECT COUNT(e)
        FROM UsageEvent e
        WHERE e.userId = :userId
          AND e.createdAt >= :since
        """)
    int countRequestsByUserSince(@Param("userId") String userId, @Param("since") Instant since);
}
