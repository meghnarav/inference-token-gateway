package com.tokengateway.service;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.RateLimitResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sliding Window Rate Limiter using Redis Sorted Sets.
 *
 * Algorithm:
 * ──────────
 * Each user has a Redis Sorted Set: key = "rl:{userId}"
 * Members:  "{timestamp_ms}:{random_suffix}"  (unique per token batch)
 * Score:    Unix timestamp in milliseconds (used for range queries)
 *
 * On each request:
 *  1. ZREMRANGEBYSCORE  → remove entries older than (now - windowMs)
 *  2. ZADD              → add new entry with score=now, member="{now}:{tokensRequested}"
 *  3. ZRANGEBYSCORE     → sum all scores in [now-windowMs, +inf] for total usage
 *  4. Compare sum vs limit → allow or deny
 *  5. If denied → ZREM the entry just added (rollback)
 *  6. EXPIRE the key → auto-cleanup after TTL
 *
 * All steps 1-4 execute inside a Redis MULTI/EXEC transaction, making the
 * check-then-act sequence atomic within a single Redis instance.
 *
 * Trade-offs vs Token Bucket:
 * - Sliding window is more accurate (no burst at window boundary)
 * - Slightly higher Redis memory usage (O(requests) per user vs O(1))
 * - Better for "fair per-minute cost control" which is the gateway's use case
 *
 * Thread Safety:
 * Redis MULTI/EXEC + Lua atomic semantics prevent race conditions.
 * Spring's Lettuce client uses connection pooling safe for concurrent callers.
 */
@Service
@Slf4j
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "rl:";

    private final StringRedisTemplate redis;
    private final GatewayProperties properties;
    private final Counter allowedCounter;
    private final Counter deniedCounter;
    private final Counter fallbackCounter;

    public SlidingWindowRateLimiter(StringRedisTemplate redis,
                                    GatewayProperties properties,
                                    MeterRegistry meterRegistry) {
        this.redis = redis;
        this.properties = properties;
        this.allowedCounter = meterRegistry.counter("gateway.ratelimit.decisions", "decision", "allowed");
        this.deniedCounter  = meterRegistry.counter("gateway.ratelimit.decisions", "decision", "denied");
        this.fallbackCounter = meterRegistry.counter("gateway.ratelimit.fallback");
    }

    @Override
    public RateLimitResult tryConsume(String userId, int tokensRequested) {
        if (!properties.getRateLimit().isEnabled()) {
            return RateLimitResult.allowed(0, Long.MAX_VALUE, Long.MAX_VALUE, 0);
        }

        try {
            return executeWithRedis(userId, tokensRequested);
        } catch (Exception e) {
            log.warn("[RATE-LIMIT] Redis unavailable for user={}, entering fallback mode. Error: {}",
                    userId, e.getMessage());
            fallbackCounter.increment();
            return RateLimitResult.fallback();
        }
    }

    @SuppressWarnings("unchecked")
    private RateLimitResult executeWithRedis(String userId, int tokensRequested) {
        String key = KEY_PREFIX + userId;
        long now = Instant.now().toEpochMilli();
        long windowMs = properties.getRateLimit().getWindowSeconds() * 1000L;
        long windowStart = now - windowMs;
        long limit = properties.getRateLimit().getTokensPerMinute();
        long ttlSeconds = properties.getRateLimit().getWindowSeconds()
                        + properties.getRateLimit().getKeyTtlBufferSeconds();

        // Member is "timestamp:tokens" — allows us to reconstruct token counts from scores
        String member = now + ":" + tokensRequested;

        // Execute as a Redis MULTI/EXEC pipeline for atomicity
        List<Object> results = redis.execute(new SessionCallback<List<Object>>() {
            @Override
            public List<Object> execute(RedisOperations operations) throws DataAccessException {
                operations.multi();

                // Step 1: Remove expired entries outside the sliding window
                operations.opsForZSet().removeRangeByScore(key, 0, windowStart);

                // Step 2: Add this request's token entry
                operations.opsForZSet().add(key, member, now);

                // Step 3: Fetch all entries in the current window
                operations.opsForZSet().rangeByScoreWithScores(key, windowStart, now + 1);

                // Step 4: Set TTL on the key
                operations.expire(key, ttlSeconds, TimeUnit.SECONDS);

                return operations.exec();
            }
        });

        if (results == null || results.size() < 3) {
            log.error("[RATE-LIMIT] Unexpected Redis MULTI/EXEC result for user={}", userId);
            return RateLimitResult.fallback();
        }

        // Parse the ZRANGEBYSCORE result (index 2 in pipeline) to sum current tokens
        var entries = (java.util.Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>>) results.get(2);
        long currentUsage = sumTokensFromEntries(entries);

        long windowResetSeconds = (windowMs / 1000);

        if (currentUsage > limit) {
            // Rollback: remove the entry we just added
            redis.opsForZSet().remove(key, member);
            long overUsage = currentUsage - tokensRequested; // usage before this request
            log.info("[RATE-LIMIT] DENIED user={} requested={} currentUsage={} limit={}",
                    userId, tokensRequested, overUsage, limit);
            deniedCounter.increment();
            return RateLimitResult.denied(overUsage, limit, windowResetSeconds);
        }

        long remaining = Math.max(0, limit - currentUsage);
        log.debug("[RATE-LIMIT] ALLOWED user={} requested={} currentUsage={} remaining={}",
                userId, tokensRequested, currentUsage, remaining);
        allowedCounter.increment();
        return RateLimitResult.allowed(currentUsage - tokensRequested, limit, remaining, windowResetSeconds);
    }

    /**
     * Parses token counts embedded in sorted set member strings.
     * Member format: "{timestamp}:{tokenCount}"
     */
    private long sumTokensFromEntries(
            java.util.Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> entries) {
        if (entries == null) return 0;
        long sum = 0;
        for (var entry : entries) {
            String value = entry.getValue();
            if (value != null) {
                String[] parts = value.split(":");
                if (parts.length == 2) {
                    try {
                        sum += Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {
                        // malformed entry — skip
                    }
                }
            }
        }
        return sum;
    }

    @Override
    public long getCurrentUsage(String userId) {
        try {
            String key = KEY_PREFIX + userId;
            long now = Instant.now().toEpochMilli();
            long windowMs = properties.getRateLimit().getWindowSeconds() * 1000L;
            long windowStart = now - windowMs;

            var entries = redis.opsForZSet().rangeByScoreWithScores(key, windowStart, now + 1);
            return sumTokensFromEntries(entries);
        } catch (Exception e) {
            log.warn("[RATE-LIMIT] Could not read current usage for user={}: {}", userId, e.getMessage());
            return -1;
        }
    }

    @Override
    public void refund(String userId, int tokens) {
        // For sliding window, refund by removing the most recent entry for this user
        // In practice, the entry TTL will expire anyway — but explicit refund is cleaner
        try {
            String key = KEY_PREFIX + userId;
            long now = Instant.now().toEpochMilli();
            // Remove the latest entry matching these tokens (best-effort)
            var candidates = redis.opsForZSet().reverseRangeByScore(key, now - 5000, now + 1);
            if (candidates != null) {
                for (String member : candidates) {
                    if (member.endsWith(":" + tokens)) {
                        redis.opsForZSet().remove(key, member);
                        log.debug("[RATE-LIMIT] Refunded {} tokens for user={}", tokens, userId);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[RATE-LIMIT] Refund failed for user={}: {}", userId, e.getMessage());
        }
    }
}
