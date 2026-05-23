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

import java.util.Map;

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

    @GetMapping("/")
    public ResponseEntity<?> root() {
        return ResponseEntity.ok(
            java.util.Map.of(
                "service", "inference-token-gateway",
                "status", "running"
            )
        );
    }

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

    @GetMapping("/usage/{userId}")
    public ResponseEntity<UsageResponse> getUsage(@PathVariable String userId) {
        UsageResponse usage = gatewayService.getUsage(userId);
        return ResponseEntity.ok(usage);
    }
}
