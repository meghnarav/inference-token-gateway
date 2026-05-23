package com.tokengateway.model;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Daily aggregated usage summary per user.
 * Updated atomically via UPSERT on each successful request.
 * Serves as a fast read path for GET /usage/{userId} without
 * requiring expensive aggregation over usage_events.
 */
@Entity
@Table(name = "user_usage_summary")
@IdClass(UserUsageSummary.UserUsageSummaryId.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserUsageSummary {

    @Id
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Id
    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "total_tokens", nullable = false)
    @Builder.Default
    private long totalTokens = 0;

    @Column(name = "total_requests", nullable = false)
    @Builder.Default
    private int totalRequests = 0;

    @Column(name = "cache_hit_count", nullable = false)
    @Builder.Default
    private int cacheHitCount = 0;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserUsageSummaryId implements Serializable {
        private String userId;
        private LocalDate usageDate;
    }
}
