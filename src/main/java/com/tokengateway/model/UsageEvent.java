package com.tokengateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Immutable audit log of every inference request processed by the gateway.
 * This is the append-only source of truth for usage billing and analytics.
 */
@Entity
@Table(
    name = "usage_events",
    indexes = {
        @Index(name = "idx_usage_events_user_id",    columnList = "user_id"),
        @Index(name = "idx_usage_events_created_at", columnList = "created_at"),
        @Index(name = "idx_usage_events_prompt_hash",columnList = "prompt_hash")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "usage_events_seq")
    @SequenceGenerator(name = "usage_events_seq", sequenceName = "usage_events_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "prompt_hash", nullable = false, length = 64)
    private String promptHash;

    @Column(name = "prompt_length", nullable = false)
    private int promptLength;

    @Column(name = "tokens_consumed", nullable = false)
    private int tokensConsumed;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "model_id", nullable = false, length = 64)
    @Builder.Default
    private String modelId = "simulated-v1";

    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "SUCCESS";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
