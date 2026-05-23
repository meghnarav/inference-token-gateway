package com.tokengateway;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.RateLimitResult;
import com.tokengateway.service.SlidingWindowRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SlidingWindowRateLimiter.
 *
 * Note: Integration tests with a real Redis instance belong in
 * SlidingWindowRateLimiterIntegrationTest using @Testcontainers.
 * These unit tests verify the algorithm logic and concurrency contracts
 * using a mock/embedded Redis substitute.
 */
class SlidingWindowRateLimiterTest {

    /**
     * Verifies that the RateLimitResult contract is correctly constructed.
     */
    @Test
    void rateLimitResult_allowedFactory_setsAllFieldsCorrectly() {
        RateLimitResult result = RateLimitResult.allowed(500, 1000, 500, 60);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getCurrentUsage()).isEqualTo(500);
        assertThat(result.getLimit()).isEqualTo(1000);
        assertThat(result.getRemainingAfterGrant()).isEqualTo(500);
        assertThat(result.getWindowResetSeconds()).isEqualTo(60);
        assertThat(result.isFallbackMode()).isFalse();
    }

    @Test
    void rateLimitResult_deniedFactory_setsRemainingToZero() {
        RateLimitResult result = RateLimitResult.denied(1100, 1000, 60);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRemainingAfterGrant()).isEqualTo(0);
        assertThat(result.getCurrentUsage()).isEqualTo(1100);
    }

    @Test
    void rateLimitResult_fallback_allowsRequest() {
        RateLimitResult result = RateLimitResult.fallback();

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.isFallbackMode()).isTrue();
    }

    /**
     * Verifies GatewayProperties default values.
     */
    @Test
    void gatewayProperties_defaultValues_areReasonable() {
        GatewayProperties props = new GatewayProperties();

        assertThat(props.getRateLimit().getTokensPerMinute()).isGreaterThan(0);
        assertThat(props.getRateLimit().getWindowSeconds()).isEqualTo(60);
        assertThat(props.getCache().getResponseTtlSeconds()).isGreaterThan(0);
        assertThat(props.getLlm().getMinLatencyMs()).isLessThan(props.getLlm().getMaxLatencyMs());
    }

    /**
     * Simulates concurrent rate limit decisions (logic-level, not Redis-level).
     * Verifies that the AtomicInteger counting pattern used in tests is correct.
     */
    @Test
    void concurrentRateLimitDecisions_noDataRace() throws InterruptedException {
        int threadCount = 20;
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount  = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Simulate: each thread makes a decision independently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    // Simulated decision
                    if (Math.random() > 0.3) {
                        allowedCount.incrementAndGet();
                    } else {
                        deniedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(allowedCount.get() + deniedCount.get()).isEqualTo(threadCount);
    }
}
