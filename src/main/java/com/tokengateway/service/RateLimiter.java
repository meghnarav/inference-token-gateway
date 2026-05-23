package com.tokengateway.service;

import com.tokengateway.dto.RateLimitResult;

/**
 * Contract for token-based rate limiting.
 *
 * Implementations must be:
 * - Thread-safe (concurrent requests from the same user)
 * - Atomic (no TOCTOU race conditions)
 * - Resilient (graceful Redis failure handling)
 */
public interface RateLimiter {

    /**
     * Attempts to consume {@code tokensRequested} from the user's rate limit bucket.
     *
     * @param userId          The user identifier
     * @param tokensRequested Number of tokens to consume
     * @return RateLimitResult containing the decision and metadata
     */
    RateLimitResult tryConsume(String userId, int tokensRequested);

    /**
     * Returns the current token usage for a user in the active window
     * without consuming any tokens (read-only).
     *
     * @param userId The user identifier
     * @return Current token consumption in the sliding window, or -1 if unavailable
     */
    long getCurrentUsage(String userId);

    /**
     * Refunds tokens back to the user's bucket.
     * Called when the downstream LLM call fails after tokens were pre-consumed.
     *
     * @param userId  The user identifier
     * @param tokens  Tokens to refund
     */
    void refund(String userId, int tokens);
}
