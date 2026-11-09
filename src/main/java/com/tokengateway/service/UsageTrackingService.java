package com.tokengateway.service;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.UsageResponse;
import com.tokengateway.dto.TokenUsageEvent;
import com.tokengateway.repository.UserUsageSummaryRepository;
import com.tokengateway.util.PromptHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Manages persistent usage tracking and aggregation.
 *
 * Responsibilities:
 * - Publishing completed request usage events to Kafka
 * - Providing usage reports from PostgreSQL
 * - Periodic cleanup of expired idempotency keys
 */
@Service
@Slf4j
public class UsageTrackingService {

    private final UserUsageSummaryRepository summaryRepo;
    private final com.tokengateway.repository.GatewayUserRepository userRepo;
    private final com.tokengateway.repository.IdempotencyKeyRepository idempotencyRepo;
    private final RateLimiter rateLimiter;
    private final GatewayProperties properties;
    private final PromptHasher promptHasher;
    private final TokenUsageEventPublisher usageEventPublisher;
    private final DistributionSummary tokenConsumptionSummary;

    @Autowired
    public UsageTrackingService(UserUsageSummaryRepository summaryRepo,
                                com.tokengateway.repository.GatewayUserRepository userRepo,
                                com.tokengateway.repository.IdempotencyKeyRepository idempotencyRepo,
                                RateLimiter rateLimiter,
                                GatewayProperties properties,
                                PromptHasher promptHasher,
                                TokenUsageEventPublisher usageEventPublisher,
                                MeterRegistry meterRegistry) {
        this.summaryRepo = summaryRepo;
        this.userRepo = userRepo;
        this.idempotencyRepo = idempotencyRepo;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.promptHasher = promptHasher;
        this.usageEventPublisher = usageEventPublisher;
        this.tokenConsumptionSummary = DistributionSummary.builder("gateway.request.tokens")
                .description("Tokens consumed by completed gateway requests")
                .baseUnit("tokens")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    protected UsageTrackingService(UserUsageSummaryRepository summaryRepo,
                                   com.tokengateway.repository.GatewayUserRepository userRepo,
                                   com.tokengateway.repository.IdempotencyKeyRepository idempotencyRepo,
                                   RateLimiter rateLimiter,
                                   GatewayProperties properties,
                                   PromptHasher promptHasher,
                                   TokenUsageEventPublisher usageEventPublisher) {
        this(summaryRepo, userRepo, idempotencyRepo, rateLimiter, properties, promptHasher,
                usageEventPublisher, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /**
     * Queues a completed request for asynchronous persistence.
     * PostgreSQL insertion and daily aggregation are performed by
     * {@link TokenUsageEventConsumer}.
     *
     * @param requestId      Correlation ID
     * @param userId         User identifier
     * @param prompt         Raw prompt text
     * @param tokensConsumed Tokens used by this request
     * @param cacheHit       Whether this was a cache hit
     * @param latencyMs      End-to-end latency
     */
    public void record(String requestId, String userId, String prompt,
                       int tokensConsumed, boolean cacheHit, long latencyMs) {
        tokenConsumptionSummary.record(tokensConsumed);
        usageEventPublisher.publish(TokenUsageEvent.builder()
                .requestId(requestId)
                .userId(userId)
                .promptHash(promptHasher.hash(prompt))
                .promptLength(prompt.length())
                .tokensConsumed(tokensConsumed)
                .cacheHit(cacheHit)
                .latencyMs(latencyMs)
                .modelId("inference")
                .occurredAt(Instant.now())
                .build());
        log.info("[USAGE] Queued requestId={} userId={} tokens={} cacheHit={}",
                requestId, userId, tokensConsumed, cacheHit);
    }

    /**
     * Builds the full usage report for a user.
     * Reads from PostgreSQL for historical data + Redis for current window.
     */
    public UsageResponse getUsage(String userId) {
        // Fetch daily summaries (last 30 days)
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);
        var summaries = summaryRepo.findByUserIdAndUsageDateGreaterThanEqualOrderByUsageDateDesc(
                userId, thirtyDaysAgo);

        long totalTokens = summaries.stream().mapToLong(s -> s.getTotalTokens()).sum();
        int totalRequests = summaries.stream().mapToInt(s -> s.getTotalRequests()).sum();
        int totalCacheHits = summaries.stream().mapToInt(s -> s.getCacheHitCount()).sum();

        // Build daily breakdown
        List<UsageResponse.DailySummary> breakdown = summaries.stream()
                .map(s -> UsageResponse.DailySummary.builder()
                        .date(s.getUsageDate().format(DateTimeFormatter.ISO_DATE))
                        .tokensConsumed(s.getTotalTokens())
                        .requests(s.getTotalRequests())
                        .cacheHits(s.getCacheHitCount())
                        .build())
                .toList();

        // Current window usage from Redis (real-time)
        long currentWindowUsage = rateLimiter.getCurrentUsage(userId);

        double cacheHitRate = totalRequests > 0
                ? (double) totalCacheHits / totalRequests
                : 0.0;

        return UsageResponse.builder()
                .userId(userId)
                .totalTokensConsumed(totalTokens)
                .totalRequests(totalRequests)
                .cacheHitCount(totalCacheHits)
                .cacheHitRate(Math.round(cacheHitRate * 1000.0) / 1000.0)
                .tokensUsedThisMinute(Math.max(0, currentWindowUsage))
                .tokensPerMinuteLimit(properties.getRateLimit().getTokensPerMinute())
                .dailyBreakdown(breakdown)
                .queriedAt(Instant.now())
                .build();
    }

    /**
     * Stores an idempotency key after processing a request.
     */
    @Transactional
    public void storeIdempotencyKey(String idempotencyKey, String requestId,
                                    String userId, String responseJson) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return;

        Instant expiresAt = Instant.now().plusSeconds(properties.getIdempotency().getTtlSeconds());
        var entity = com.tokengateway.model.IdempotencyKey.builder()
                .idempotencyKey(idempotencyKey)
                .requestId(requestId)
                .userId(userId)
                .responseBody(responseJson)
                .expiresAt(expiresAt)
                .build();
        idempotencyRepo.save(entity);
    }

    /**
     * Looks up a stored idempotency key (for replay detection).
     */
    public java.util.Optional<com.tokengateway.model.IdempotencyKey> findIdempotencyKey(
            String idempotencyKey, String userId) {
        return idempotencyRepo.findByIdempotencyKeyAndUserId(idempotencyKey, userId);
    }

    /**
     * Periodic cleanup: remove expired idempotency keys.
     * Runs every hour.
     */
    @Scheduled(fixedRateString = "3600000")
    @Transactional
    public void cleanupExpiredIdempotencyKeys() {
        int deleted = idempotencyRepo.deleteExpiredKeys(Instant.now());
        if (deleted > 0) {
            log.info("[CLEANUP] Removed {} expired idempotency keys", deleted);
        }
    }
}
