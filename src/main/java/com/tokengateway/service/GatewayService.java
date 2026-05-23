package com.tokengateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengateway.dto.GenerateRequest;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.dto.RateLimitResult;
import com.tokengateway.dto.UsageResponse;
import com.tokengateway.exception.RateLimitExceededException;
import com.tokengateway.exception.UserNotFoundException;
import com.tokengateway.util.RequestIdGenerator;
import com.tokengateway.util.TokenCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Central orchestrator for the inference gateway pipeline.
 *
 * Request lifecycle:
 * ─────────────────
 * 1. Generate correlation IDs, set MDC context for structured logging
 * 2. Check idempotency key → return stored response if replay detected
 * 3. Pre-flight token estimation for rate limit check
 * 4. Rate limit check (sliding window via Redis)
 * 5. Check prompt cache (Redis)
 *    → HIT: return cached response, record reduced usage, skip step 6
 *    → MISS: proceed to LLM call
 * 6. Call LLM backend (simulated or real)
 * 7. Refund rate limit tokens if actual usage < estimated
 * 8. Store response in cache
 * 9. Record usage to PostgreSQL (async-safe, idempotent)
 * 10. Store idempotency key
 * 11. Return response with remaining quota metadata
 *
 * Thread Safety:
 * - Rate limiter uses Redis MULTI/EXEC for atomicity
 * - Cache operations are isolated fail-safe
 * - Usage recording is transactional
 */
@Service
@Slf4j
public class GatewayService {

    private final RateLimiter rateLimiter;
    private final ResponseCache responseCache;
    private final LlmBackend llmBackend;
    private final UsageTrackingService usageTracker;
    private final RequestIdGenerator requestIdGen;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final Timer requestTimer;

    public GatewayService(RateLimiter rateLimiter,
                          ResponseCache responseCache,
                          LlmBackend llmBackend,
                          UsageTrackingService usageTracker,
                          RequestIdGenerator requestIdGen,
                          TokenCounter tokenCounter,
                          ObjectMapper objectMapper,
                          MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.responseCache = responseCache;
        this.llmBackend = llmBackend;
        this.usageTracker = usageTracker;
        this.requestIdGen = requestIdGen;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
        this.requestTimer = Timer.builder("gateway.request.duration")
                .description("End-to-end request processing time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Processes a generate request through the full gateway pipeline.
     */
    public GenerateResponse processGenerate(GenerateRequest request) {
        String requestId = requestIdGen.generate();
        long startMs = System.currentTimeMillis();

        // Set MDC for structured logging (propagated across log statements in this thread)
        MDC.put("requestId", requestId);
        MDC.put("userId", request.getUserId());

        try {
            return requestTimer.record(() -> {
                try {
                    return doProcess(requestId, request, startMs);
                } catch (Exception e) {
                    // Re-throw after logging; MDC is cleared in finally
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RateLimitExceededException rle) throw rle;
            if (cause instanceof UserNotFoundException une) throw une;
            throw e;
        } finally {
            MDC.clear();
        }
    }

    private GenerateResponse doProcess(String requestId, GenerateRequest request, long startMs) {
        String userId  = request.getUserId();
        String prompt  = request.getPrompt();
        String model   = request.getModel();

        log.info("[GATEWAY] REQUEST RECEIVED requestId={} userId={} promptLength={}",
                requestId, userId, prompt.length());

        // Step 1: Idempotency check
        if (request.getIdempotencyKey() != null) {
            var existing = usageTracker.findIdempotencyKey(request.getIdempotencyKey(), userId);
            if (existing.isPresent()) {
                log.info("[GATEWAY] IDEMPOTENT REPLAY requestId={} idempotencyKey={}",
                        requestId, request.getIdempotencyKey());
                try {
                    return objectMapper.readValue(existing.get().getResponseBody(), GenerateResponse.class);
                } catch (JsonProcessingException e) {
                    log.warn("[GATEWAY] Failed to deserialize cached idempotency response, reprocessing");
                }
            }
        }

        // Step 2: Pre-flight token estimation (conservative: prompt tokens only, min 50)
        int estimatedTokens = Math.max(50, tokenCounter.estimateTokens(prompt));

        // Step 3: Rate limit check
        RateLimitResult rateLimitResult = rateLimiter.tryConsume(userId, estimatedTokens);
        log.info("[GATEWAY] RATE_LIMIT_DECISION requestId={} userId={} allowed={} estimatedTokens={} remaining={} fallback={}",
                requestId, userId, rateLimitResult.isAllowed(),
                estimatedTokens, rateLimitResult.getRemainingAfterGrant(), rateLimitResult.isFallbackMode());

        if (!rateLimitResult.isAllowed()) {
            throw new RateLimitExceededException(userId,
                    rateLimitResult.getCurrentUsage(),
                    rateLimitResult.getLimit(),
                    rateLimitResult.getWindowResetSeconds());
        }

        // Step 4: Cache lookup
        var cached = responseCache.get(prompt);
        if (cached.isPresent()) {
            GenerateResponse cachedResponse = cached.get();
            // Cache hit: only charge prompt tokens (no generation cost)
            int promptTokens = tokenCounter.estimateTokens(prompt);

            // Refund the difference between estimated and actual (prompt-only) cost
            int refund = estimatedTokens - promptTokens;
            if (refund > 0) {
                rateLimiter.refund(userId, refund);
            }

            long latency = System.currentTimeMillis() - startMs;
            GenerateResponse response = GenerateResponse.builder()
                    .requestId(requestId)
                    .userId(userId)
                    .response(cachedResponse.getResponse())
                    .tokensConsumed(promptTokens)
                    .cacheHit(true)
                    .latencyMs(latency)
                    .model(cachedResponse.getModel())
                    .timestamp(Instant.now())
                    .remainingTokensThisMinute(rateLimitResult.getRemainingAfterGrant())
                    .build();

            // Record reduced usage asynchronously
            recordUsageAsync(requestId, userId, prompt, promptTokens, true, latency);
            storeIdempotencyKey(request.getIdempotencyKey(), requestId, userId, response);

            log.info("[GATEWAY] CACHE_HIT requestId={} userId={} promptTokens={} latencyMs={}",
                    requestId, userId, promptTokens, latency);
            return response;
        }

        log.info("[GATEWAY] CACHE_MISS requestId={} userId={} — invoking LLM", requestId, userId);

        // Step 5: LLM invocation
        GenerateResponse llmResponse;
        try {
            llmResponse = llmBackend.generate(requestId, userId, prompt, model);
        } catch (Exception e) {
            // Refund tokens on LLM failure
            rateLimiter.refund(userId, estimatedTokens);
            log.error("[GATEWAY] LLM_ERROR requestId={} error={}", requestId, e.getMessage(), e);
            throw new com.tokengateway.exception.LlmBackendException("LLM backend error: " + e.getMessage(), e);
        }

        // Step 6: Refund over-estimated tokens
        int actualTokens = llmResponse.getTokensConsumed();
        if (actualTokens < estimatedTokens) {
            rateLimiter.refund(userId, estimatedTokens - actualTokens);
        }

        // Step 7: Store in cache
        responseCache.put(prompt, llmResponse);

        long totalLatency = System.currentTimeMillis() - startMs;
        GenerateResponse response = GenerateResponse.builder()
                .requestId(requestId)
                .userId(userId)
                .response(llmResponse.getResponse())
                .tokensConsumed(actualTokens)
                .cacheHit(false)
                .latencyMs(totalLatency)
                .model(llmResponse.getModel())
                .timestamp(Instant.now())
                .remainingTokensThisMinute(rateLimitResult.getRemainingAfterGrant())
                .build();

        // Step 8: Record usage
        recordUsageAsync(requestId, userId, prompt, actualTokens, false, totalLatency);
        storeIdempotencyKey(request.getIdempotencyKey(), requestId, userId, response);

        log.info("[GATEWAY] REQUEST_COMPLETE requestId={} userId={} tokens={} latencyMs={} cached=false",
                requestId, userId, actualTokens, totalLatency);
        return response;
    }

    public UsageResponse getUsage(String userId) {
        MDC.put("userId", userId);
        try {
            log.info("[GATEWAY] USAGE_QUERY userId={}", userId);
            return usageTracker.getUsage(userId);
        } finally {
            MDC.clear();
        }
    }

    private void recordUsageAsync(String requestId, String userId, String prompt,
                                   int tokens, boolean cacheHit, long latencyMs) {
        // In production, this would be async (e.g., @Async or publish to internal queue)
        // Kept synchronous here for transactional correctness demonstration
        try {
            usageTracker.record(requestId, userId, prompt, tokens, cacheHit, latencyMs);
        } catch (Exception e) {
            // Non-fatal: usage tracking failure should not fail the request
            log.error("[GATEWAY] Usage recording failed for requestId={}: {}", requestId, e.getMessage(), e);
        }
    }

    private void storeIdempotencyKey(String idempotencyKey, String requestId,
                                      String userId, GenerateResponse response) {
        if (idempotencyKey == null) return;
        try {
            String json = objectMapper.writeValueAsString(response);
            usageTracker.storeIdempotencyKey(idempotencyKey, requestId, userId, json);
        } catch (Exception e) {
            log.warn("[GATEWAY] Failed to store idempotency key={}: {}", idempotencyKey, e.getMessage());
        }
    }
}
