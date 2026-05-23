package com.tokengateway.controller;

import com.tokengateway.dto.GenerateRequest;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.dto.UsageResponse;
import com.tokengateway.service.GatewayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Primary REST controller for the Inference Token Gateway.
 *
 * Endpoints:
 *   POST /generate          - Submit a prompt for LLM inference
 *   GET  /usage/{userId}    - Retrieve token usage statistics for a user
 *   GET  /health            - Simple liveness check (structural, Actuator has detailed health)
 *
 * This layer is intentionally thin — it delegates all business logic
 * to GatewayService and exception handling to GlobalExceptionHandler.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;

    /**
     * Submit a prompt for LLM inference.
     *
     * Enforces:
     * - Input validation (@Valid)
     * - Token-based rate limiting per user
     * - Prompt-level response caching
     * - Idempotency (optional idempotencyKey field)
     *
     * @param request GenerateRequest containing userId, prompt, and optional idempotencyKey
     * @return GenerateResponse with the model output and usage metadata
     */
    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@Valid @RequestBody GenerateRequest request) {
        GenerateResponse response = gatewayService.processGenerate(request);
        return ResponseEntity.ok()
                .header("X-Request-Id", response.getRequestId())
                .header("X-Cache-Hit", String.valueOf(response.isCacheHit()))
                .header("X-Tokens-Consumed", String.valueOf(response.getTokensConsumed()))
                .header("X-RateLimit-Remaining", String.valueOf(response.getRemainingTokensThisMinute()))
                .body(response);
    }

    /**
     * Retrieve usage statistics for a given user.
     *
     * Returns:
     * - Historical usage from PostgreSQL (last 30 days)
     * - Current sliding window usage from Redis
     * - Cache hit rate
     * - Per-day breakdown
     *
     * @param userId The user ID to query
     * @return UsageResponse with aggregated and per-day statistics
     */
    @GetMapping("/usage/{userId}")
    public ResponseEntity<UsageResponse> getUsage(@PathVariable String userId) {
        UsageResponse usage = gatewayService.getUsage(userId);
        return ResponseEntity.ok(usage);
    }
}
