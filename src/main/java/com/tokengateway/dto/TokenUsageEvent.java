package com.tokengateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;

/**
 * Kafka payload for asynchronously persisting completed inference usage.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenUsageEvent(
        String requestId,
        String userId,
        String promptHash,
        int promptLength,
        int tokensConsumed,
        boolean cacheHit,
        long latencyMs,
        String modelId,
        Instant occurredAt
) {
}
