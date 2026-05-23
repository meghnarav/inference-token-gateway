package com.tokengateway.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Result object returned by the rate limiter.
 * Encapsulates both the decision and metadata for logging/response headers.
 */
@Data
@Builder
public class RateLimitResult {

    /** Whether the request is allowed to proceed */
    private boolean allowed;

    /** Current token count in this window BEFORE this request */
    private long currentUsage;

    /** Maximum tokens allowed per window */
    private long limit;

    /** Remaining tokens after granting this request (only meaningful if allowed=true) */
    private long remainingAfterGrant;

    /** Seconds until the window resets (approximate) */
    private long windowResetSeconds;

    /** Whether this result came from a Redis fallback (degraded mode) */
    private boolean fallbackMode;

    /**
     * Tokens requested for this operation.
     * Used to pre-check before actual LLM invocation.
     */
    private int tokensRequested;

    public static RateLimitResult allowed(long currentUsage, long limit, long remainingAfterGrant, long windowReset) {
        return RateLimitResult.builder()
                .allowed(true)
                .currentUsage(currentUsage)
                .limit(limit)
                .remainingAfterGrant(remainingAfterGrant)
                .windowResetSeconds(windowReset)
                .fallbackMode(false)
                .build();
    }

    public static RateLimitResult denied(long currentUsage, long limit, long windowReset) {
        return RateLimitResult.builder()
                .allowed(false)
                .currentUsage(currentUsage)
                .limit(limit)
                .remainingAfterGrant(0)
                .windowResetSeconds(windowReset)
                .fallbackMode(false)
                .build();
    }

    public static RateLimitResult fallback() {
        return RateLimitResult.builder()
                .allowed(true)
                .fallbackMode(true)
                .remainingAfterGrant(-1)
                .build();
    }
}
