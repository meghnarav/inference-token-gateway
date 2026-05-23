package com.tokengateway.service;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.UsageResponse;
import com.tokengateway.model.UsageEvent;
import com.tokengateway.repository.UsageEventRepository;
import com.tokengateway.repository.UserUsageSummaryRepository;
import com.tokengateway.util.PromptHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * - Recording each request as an immutable UsageEvent
 * - Maintaining denormalized daily summaries for fast reads
 * - Auto-registering new users on first request
 * - Periodic cleanup of expired idempotency keys
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UsageTrackingService {

    private final UsageEventRepository usageEventRepo;
    private final UserUsageSummaryRepository summaryRepo;
    private final com.tokengateway.repository.GatewayUserRepository userRepo;
    private final com.tokengateway.repository.IdempotencyKeyRepository idempotencyRepo;
    private final RateLimiter rateLimiter;
    private final GatewayProperties properties;
    private final PromptHasher promptHasher;

    /**
     * Records a completed request.
     * Operations:
     * 1. Auto-register user if not exists
     * 2. Insert UsageEvent (idempotent via requestId unique constraint)
     * 3. UPSERT daily summary
     *
     * @param requestId      Correlation ID
     * @param userId         User identifier
     * @param prompt         Raw prompt text
     * @param tokensConsumed Tokens used by this request
     * @param cacheHit       Whether this was a cache hit
     * @param latencyMs      End-to-end latency
     */
    @Transactional
    public void record(String requestId, String userId, String prompt,
                       int tokensConsumed, boolean cacheHit, long latencyMs) {
        // 1. Auto-register user
        userRepo.registerIfAbsent(userId);

        // 2. Insert usage event (skip if duplicate requestId — idempotent)
        if (!usageEventRepo.existsByRequestId(requestId)) {
            UsageEvent event = UsageEvent.builder()
                    .requestId(requestId)
                    .userId(userId)
                    .promptHash(promptHasher.hash(prompt))
                    .promptLength(prompt.length())
                    .tokensConsumed(tokensConsumed)
                    .cacheHit(cacheHit)
                    .latencyMs((int) latencyMs)
                    .build();
            usageEventRepo.save(event);
        } else {
            log.warn("[USAGE] Duplicate requestId={} — skipping insert (idempotent)", requestId);
        }

        // 3. Upsert daily summary
        summaryRepo.upsertDailySummary(userId, tokensConsumed, cacheHit ? 1 : 0);

        log.info("[USAGE] Recorded requestId={} userId={} tokens={} cacheHit={}",
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
