package com.tokengateway.service;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.dto.ollama.OllamaChatRequest;
import com.tokengateway.dto.ollama.OllamaChatResponse;
import com.tokengateway.exception.LlmBackendException;
import com.tokengateway.util.TokenCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * LlmBackend implementation for local Ollama instances.
 * Supports both blocking calls and Server-Sent Events / reactive streaming.
 */
@Service("ollamaLlmBackend")
@Slf4j
public class OllamaLlmBackend implements LlmBackend {

    private static final String CHAT_PATH = "/api/chat";
    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant. Respond clearly and concisely.";

    private final WebClient webClient;
    private final GatewayProperties properties;
    private final TokenCounter tokenCounter;
    private final Counter successCounter;
    private final Counter errorCounter;
    private final Timer latencyTimer;

    public OllamaLlmBackend(@Qualifier("ollamaWebClient") WebClient webClient,
                             GatewayProperties properties,
                             TokenCounter tokenCounter,
                             MeterRegistry meterRegistry) {
        this.webClient      = webClient;
        this.properties     = properties;
        this.tokenCounter   = tokenCounter;
        this.successCounter = meterRegistry.counter("gateway.llm.requests", "provider", "ollama", "result", "success");
        this.errorCounter   = meterRegistry.counter("gateway.llm.requests", "provider", "ollama", "result", "error");
        this.latencyTimer   = Timer.builder("gateway.llm.latency")
                .tag("provider", "ollama")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    public GenerateResponse generate(String requestId, String userId, String prompt, String model) {
        GatewayProperties.LlmProperties llm = properties.getLlm();
        String resolvedModel = (model != null && !model.isBlank()) ? model : llm.getOllamaDefaultModel();

        log.info("[OLLAMA] requestId={} userId={} model={} promptLen={}", requestId, userId, resolvedModel, prompt.length());

        OllamaChatRequest chatRequest = OllamaChatRequest.builder()
                .model(resolvedModel)
                .messages(List.of(
                        OllamaChatRequest.Message.system(SYSTEM_PROMPT),
                        OllamaChatRequest.Message.user(prompt)
                ))
                .stream(false)
                .build();

        long startMs = System.currentTimeMillis();

        OllamaChatResponse chatResponse = latencyTimer.record(() ->
                callWithRetry(chatRequest, requestId, llm.getMaxRetries(), llm.getRetryBackoffMs())
        );

        long latencyMs = System.currentTimeMillis() - startMs;

        int totalTokens = chatResponse.totalTokens() > 0
                ? chatResponse.totalTokens()
                : tokenCounter.estimateTokens(prompt) + tokenCounter.estimateTokens(chatResponse.content());

        String responseText = chatResponse.content();
        String usedModel    = chatResponse.getModel() != null ? chatResponse.getModel() : resolvedModel;

        log.info("[OLLAMA] requestId={} userId={} model={} tokens={} latencyMs={}", requestId, userId, usedModel, totalTokens, latencyMs);
        successCounter.increment();

        return GenerateResponse.builder()
                .requestId(requestId)
                .userId(userId)
                .response(responseText)
                .tokensConsumed(totalTokens)
                .cacheHit(false)
                .latencyMs(latencyMs)
                .model(usedModel)
                .timestamp(Instant.now())
                .build();
    }

    @Override
    public Flux<String> stream(String requestId, String userId, String prompt, String model) {
        GatewayProperties.LlmProperties llm = properties.getLlm();
        String resolvedModel = (model != null && !model.isBlank()) ? model : llm.getOllamaDefaultModel();

        log.info("[OLLAMA-STREAM] START requestId={} userId={} model={}", requestId, userId, resolvedModel);

        OllamaChatRequest chatRequest = OllamaChatRequest.builder()
                .model(resolvedModel)
                .messages(List.of(
                        OllamaChatRequest.Message.system(SYSTEM_PROMPT),
                        OllamaChatRequest.Message.user(prompt)
                ))
                .stream(true)
                .build();

        return webClient.post()
                .uri(CHAT_PATH)
                .bodyValue(chatRequest)
                .retrieve()
                .bodyToFlux(OllamaChatResponse.class)
                .takeUntil(OllamaChatResponse::isDone)
                .map(OllamaChatResponse::content)
                .filter(c -> c != null && !c.isEmpty())
                .doOnComplete(() -> {
                    successCounter.increment();
                    log.info("[OLLAMA-STREAM] COMPLETE requestId={}", requestId);
                })
                .doOnError(err -> {
                    errorCounter.increment();
                    log.error("[OLLAMA-STREAM] ERROR requestId={} error={}", requestId, err.getMessage());
                });
    }

    private OllamaChatResponse callWithRetry(OllamaChatRequest request,
                                              String requestId,
                                              int maxRetries,
                                              int backoffMs) {
        try {
            return webClient.post()
                    .uri(CHAT_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), resp ->
                            resp.bodyToMono(String.class).flatMap(body -> {
                                log.error("[OLLAMA] 4xx error requestId={}: {}", requestId, body);
                                return reactor.core.publisher.Mono.error(
                                        new LlmBackendException("Ollama client error: " + body, null));
                            })
                    )
                    .onStatus(status -> status.is5xxServerError(), resp ->
                            resp.bodyToMono(String.class).flatMap(body -> {
                                log.warn("[OLLAMA] 5xx error requestId={}, will retry: {}", requestId, body);
                                return reactor.core.publisher.Mono.error(
                                        new org.springframework.web.reactive.function.client.WebClientResponseException(
                                                500, "Ollama server error", null, null, null));
                            })
                    )
                    .bodyToMono(OllamaChatResponse.class)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(backoffMs))
                            .filter(ex -> !(ex instanceof LlmBackendException))
                            .onRetryExhaustedThrow((spec, signal) ->
                                    new LlmBackendException("Ollama unavailable after " + maxRetries + " retries", signal.failure())
                            )
                    )
                    .block();
        } catch (LlmBackendException e) {
            errorCounter.increment();
            throw e;
        } catch (Exception e) {
            errorCounter.increment();
            log.error("[OLLAMA] Unexpected error requestId={}: {}", requestId, e.getMessage(), e);
            throw new LlmBackendException("Ollama request failed: " + e.getMessage(), e);
        }
    }
}
