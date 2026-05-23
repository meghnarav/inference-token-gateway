package com.tokengateway.exception;

import lombok.Getter;

/**
 * Thrown when a user's token consumption exceeds the rate limit for the current window.
 * Maps to HTTP 429 Too Many Requests.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final String userId;
    private final long currentUsage;
    private final long limit;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String userId, long currentUsage, long limit, long retryAfterSeconds) {
        super(String.format("Rate limit exceeded for user=%s: usage=%d limit=%d", userId, currentUsage, limit));
        this.userId = userId;
        this.currentUsage = currentUsage;
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
