package com.tokengateway.service;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.util.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates an LLM inference backend for testing and development.
 *
 * Behavior:
 * - Introduces realistic latency (configurable range in application.yml)
 * - Generates contextually-named fake responses based on prompt keywords
 * - Computes realistic token counts using the TokenCounter heuristic
 * - Structured to be a drop-in replacement with real API clients
 *
 * To swap in a real backend (e.g., OpenAI):
 *   1. Implement LlmBackend
 *   2. Inject API key via @Value
 *   3. Add @Primary or @Profile("production")
 *   4. Remove @Primary from this bean (or use profiles)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SimulatedLlmBackend implements LlmBackend {

    private static final String MODEL_ID = "simulated-v1";
    private static final String[] RESPONSE_TEMPLATES = {
        "Based on your query about %s, here is a comprehensive analysis: The topic involves multiple dimensions that require careful consideration. Key factors include scalability, reliability, and performance characteristics which must be balanced against operational complexity.",
        "Regarding %s: This is a multifaceted subject. From a technical standpoint, the primary considerations are latency, throughput, and fault tolerance. Production systems should implement circuit breakers and fallback mechanisms to handle partial failures gracefully.",
        "Your question about %s touches on fundamental principles of distributed systems. When designing for scale, engineers must account for network partitions, eventual consistency trade-offs, and the CAP theorem's implications on system design.",
        "On the topic of %s: Modern software engineering practices emphasize observability, testability, and maintainability. A well-designed system includes structured logging, distributed tracing, and metrics collection to enable rapid diagnosis of production issues.",
        "Analyzing %s from first principles: The solution space has several viable approaches. Option A prioritizes consistency at the cost of availability. Option B maximizes availability with eventual consistency. The choice depends on the specific SLA requirements of your use case.",
    };

    private final GatewayProperties properties;
    private final TokenCounter tokenCounter;

    @Override
    public GenerateResponse generate(String requestId, String userId, String prompt, String model) {
        long startMs = System.currentTimeMillis();

        // Simulate LLM inference latency
        int latencyMs = simulateLatency();

        // Generate a contextual fake response
        String responseText = generateResponse(prompt);

        // Count tokens (prompt + response)
        int promptTokens  = tokenCounter.estimateTokens(prompt);
        int responseTokens = tokenCounter.estimateTokens(responseText);
        int totalTokens = promptTokens + responseTokens;

        long actualLatency = System.currentTimeMillis() - startMs;

        log.info("[LLM] requestId={} userId={} promptTokens={} responseTokens={} totalTokens={} latencyMs={}",
                requestId, userId, promptTokens, responseTokens, totalTokens, actualLatency);

        return GenerateResponse.builder()
                .requestId(requestId)
                .userId(userId)
                .response(responseText)
                .tokensConsumed(totalTokens)
                .cacheHit(false)
                .latencyMs(actualLatency)
                .model(model != null ? model : MODEL_ID)
                .timestamp(Instant.now())
                .build();
    }

    private int simulateLatency() {
        int min = properties.getLlm().getMinLatencyMs();
        int max = properties.getLlm().getMaxLatencyMs();
        int latency = ThreadLocalRandom.current().nextInt(min, max + 1);
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return latency;
    }

    private String generateResponse(String prompt) {
        // Extract meaningful keywords from the prompt for contextual responses
        String keyword = extractKeyword(prompt);
        String template = RESPONSE_TEMPLATES[Math.abs(prompt.hashCode()) % RESPONSE_TEMPLATES.length];
        return String.format(template, keyword);
    }

    private String extractKeyword(String prompt) {
        // Simple keyword extraction: find the longest non-common word
        String[] words = prompt.toLowerCase().split("\\s+");
        String[] stopWords = {"what", "is", "are", "the", "a", "an", "how", "why", "when", "where", "tell", "me", "about", "explain"};
        java.util.Set<String> stopSet = new java.util.HashSet<>(java.util.Arrays.asList(stopWords));

        return java.util.Arrays.stream(words)
                .filter(w -> w.length() > 3 && !stopSet.contains(w))
                .findFirst()
                .orElse("this topic");
    }
}
