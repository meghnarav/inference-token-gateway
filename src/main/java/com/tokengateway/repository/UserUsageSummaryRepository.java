package com.tokengateway.repository;

import com.tokengateway.model.UserUsageSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface UserUsageSummaryRepository extends JpaRepository<UserUsageSummary, UserUsageSummary.UserUsageSummaryId> {

    List<UserUsageSummary> findByUserIdOrderByUsageDateDesc(String userId);

    List<UserUsageSummary> findByUserIdAndUsageDateGreaterThanEqualOrderByUsageDateDesc(
            String userId, LocalDate since);

    /**
     * Atomic UPSERT: insert new daily summary row or increment existing one.
     * Uses PostgreSQL's ON CONFLICT DO UPDATE for true atomicity.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO user_usage_summary (user_id, usage_date, total_tokens, total_requests, cache_hit_count, updated_at)
        VALUES (:userId, CURRENT_DATE, :tokens, 1, :cacheHit, NOW())
        ON CONFLICT (user_id, usage_date) DO UPDATE SET
            total_tokens    = user_usage_summary.total_tokens + :tokens,
            total_requests  = user_usage_summary.total_requests + 1,
            cache_hit_count = user_usage_summary.cache_hit_count + :cacheHit,
            updated_at      = NOW()
        """, nativeQuery = true)
    void upsertDailySummary(
            @Param("userId")   String userId,
            @Param("tokens")   int tokens,
            @Param("cacheHit") int cacheHit);
}
