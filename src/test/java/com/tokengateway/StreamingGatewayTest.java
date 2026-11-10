package com.tokengateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengateway.dto.GenerateRequest;
import com.tokengateway.dto.RateLimitResult;
import com.tokengateway.dto.StreamChunk;
import com.tokengateway.model.IdempotencyKey;
import com.tokengateway.service.GatewayService;
import com.tokengateway.service.LlmBackend;
import com.tokengateway.service.RateLimiter;
import com.tokengateway.service.ResponseCache;
import com.tokengateway.service.UsageTrackingService;
import com.tokengateway.util.RequestIdGenerator;
import com.tokengateway.util.TokenCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamingGatewayTest {

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private ResponseCache responseCache;

    @Mock
    private LlmBackend llmBackend;

    private TestableUsageTrackingService usageTracker;
    private GatewayService gatewayService;

    static class TestableUsageTrackingService extends UsageTrackingService {
        final List<String> recordedUsers = new ArrayList<>();
        final List<Integer> recordedTokens = new ArrayList<>();

        public TestableUsageTrackingService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public void record(String requestId, String userId, String prompt, int tokens, boolean cacheHit, long latencyMs) {
            recordedUsers.add(userId);
            recordedTokens.add(tokens);
        }

        @Override
        public Optional<IdempotencyKey> findIdempotencyKey(String key, String userId) {
            return Optional.empty();
        }

        @Override
        public void storeIdempotencyKey(String key, String requestId, String userId, String json) {}
    }

    @BeforeEach
    void setUp() {
        TokenCounter tokenCounter = new TokenCounter();
        RequestIdGenerator idGen = new RequestIdGenerator();
        ObjectMapper objectMapper = new ObjectMapper();
        usageTracker = new TestableUsageTrackingService();

        gatewayService = new GatewayService(
                rateLimiter,
                responseCache,
                llmBackend,
                usageTracker,
                idGen,
                tokenCounter,
                objectMapper,
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("Streaming completes successfully with on-the-fly token counting and DB usage record")
    void testStreamingSuccessful() {
        GenerateRequest request = GenerateRequest.builder()
                .userId("alice")
                .prompt("Hello world")
                .build();

        when(responseCache.get(anyString())).thenReturn(Optional.empty());
        when(rateLimiter.tryConsume(eq("alice"), anyInt()))
                .thenReturn(RateLimitResult.allowed(10, 1000, 990, 60));

        // Mock backend streaming chunks: "Hello ", "streaming ", "world!"
        when(llmBackend.stream(anyString(), eq("alice"), eq("Hello world"), isNull()))
                .thenReturn(Flux.just("Hello ", "streaming ", "world!"));

        Flux<ServerSentEvent<StreamChunk>> stream = gatewayService.streamGenerate(request);

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    StreamChunk chunk = sse.data();
                    assertThat(chunk).isNotNull();
                    assertThat(chunk.getContent()).isEqualTo("Hello ");
                    assertThat(chunk.isDone()).isFalse();
                    assertThat(chunk.getTokensSoFar()).isGreaterThan(0);
                })
                .assertNext(sse -> {
                    StreamChunk chunk = sse.data();
                    assertThat(chunk).isNotNull();
                    assertThat(chunk.getContent()).isEqualTo("streaming ");
                    assertThat(chunk.isDone()).isFalse();
                })
                .assertNext(sse -> {
                    StreamChunk chunk = sse.data();
                    assertThat(chunk).isNotNull();
                    assertThat(chunk.getContent()).isEqualTo("world!");
                    assertThat(chunk.isDone()).isFalse();
                })
                .assertNext(sse -> {
                    StreamChunk chunk = sse.data();
                    assertThat(chunk).isNotNull();
                    assertThat(chunk.isDone()).isTrue();
                    assertThat(chunk.getFinishReason()).isEqualTo("stop");
                })
                .verifyComplete();

        // Verify usage tracking recorded upon stream completion
        assertThat(usageTracker.recordedUsers).contains("alice");
        assertThat(usageTracker.recordedTokens.get(0)).isGreaterThan(0);
        // Verify response cached in semantic cache
        verify(responseCache).put(eq("Hello world"), any());
    }

    @Test
    @DisplayName("Mid-stream rate limiting: terminates stream when tokens exceed budget on the fly")
    void testMidStreamRateLimitExceeded() {
        GenerateRequest request = GenerateRequest.builder()
                .userId("bob")
                .prompt("Tell me a story")
                .build();

        when(responseCache.get(anyString())).thenReturn(Optional.empty());
        // Pre-flight check grants initial estimate, mid-stream check denies
        when(rateLimiter.tryConsume(eq("bob"), anyInt()))
                .thenReturn(RateLimitResult.allowed(10, 50, 40, 60))
                .thenReturn(RateLimitResult.denied(50, 50, 60));

        // Generate text that clearly exceeds 50 tokens (e.g. 60+ words)
        String longChunk = "word ".repeat(60);
        when(llmBackend.stream(anyString(), eq("bob"), eq("Tell me a story"), isNull()))
                .thenReturn(Flux.just(longChunk));

        Flux<ServerSentEvent<StreamChunk>> stream = gatewayService.streamGenerate(request);

        StepVerifier.create(stream)
                .assertNext(sse -> {
                    StreamChunk chunk = sse.data();
                    assertThat(chunk).isNotNull();
                    assertThat(chunk.isDone()).isTrue();
                    assertThat(chunk.getFinishReason()).isEqualTo("rate_limit_exceeded");
                })
                .verifyComplete();
    }
}
