package com.tokengateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Stores processed idempotency keys to ensure exactly-once semantics.
 * Expired keys are periodically cleaned by a scheduled task.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 256)
    private String idempotencyKey;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
