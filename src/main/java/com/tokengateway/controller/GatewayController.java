package com.tokengateway.controller;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.GenerateRequest;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.dto.StreamChunk;
import com.tokengateway.dto.UsageResponse;
import com.tokengateway.service.GatewayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Primary REST controller for the Inference Token Gateway.
 *
 * Endpoints:
 *   POST /generate          - Submit a prompt for LLM inference
 *   GET  /usage/{userId}    - Retrieve token usage statistics for a user
 *   GET  /api/status        - Service status + active LLM provider
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;
    private final GatewayProperties gatewayProperties;

    /** Redirect bare root to the frontend SPA. */
    @GetMapping("/")
    public ResponseEntity<?> root() {
        return ResponseEntity.status(302)
                .header("Location", "/index.html")
                .build();
    }

    @GetMapping("/api/status")
    public ResponseEntity<?> apiStatus() {
        return ResponseEntity.ok(Map.of(
            "service",  "inference-token-gateway",
            "status",   "running",
            "provider", gatewayProperties.getLlm().getProvider()
        ));
    }

    @PostMapping("/generate")
    public ResponseEntity<GenerateResponse> generate(@Valid @RequestBody GenerateRequest request) {
        GenerateResponse response = gatewayService.processGenerate(request);
        return ResponseEntity.ok()
                .header("X-Request-Id",          response.getRequestId())
                .header("X-Cache-Hit",            String.valueOf(response.isCacheHit()))
                .header("X-Tokens-Consumed",      String.valueOf(response.getTokensConsumed()))
                .header("X-RateLimit-Remaining",  String.valueOf(response.getRemainingTokensThisMinute()))
                .body(response);
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamChunk>> generateStream(@Valid @RequestBody GenerateRequest request) {
        return gatewayService.streamGenerate(request);
    }

    @GetMapping("/usage/{userId}")
    public ResponseEntity<UsageResponse> getUsage(@PathVariable String userId) {
        UsageResponse usage = gatewayService.getUsage(userId);
        return ResponseEntity.ok(usage);
    }
}
