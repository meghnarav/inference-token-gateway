package com.tokengateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.dto.openai.OpenAiChatRequest;
import com.tokengateway.dto.openai.OpenAiChatResponse;
import com.tokengateway.dto.openai.OpenAiErrorResponse;
import com.tokengateway.exception.LlmBackendException;
import com.tokengateway.exception.ProviderUnavailableException;
import com.tokengateway.util.TokenCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * LlmBackend implementation that proxies to the OpenAI Chat Completions API.
 * Supports both synchronous invocation and Server-Sent Events (SSE) streaming.
 */
@Service("openAiLlmBackend")
@Slf4j
public class OpenAiLlmBackend implements LlmBackend {

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String SYSTEM_PROMPT =
            "You are a helpful assistant. Respond clearly and concisely.";

    private final WebClient webClient;
    private final GatewayProperties properties;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final Counter successCounter;
    private final Counter errorCounter;
    private final Timer latencyTimer;

    public OpenAiLlmBackend(@Qualifier("openAiWebClient") WebClient webClient,
                             GatewayProperties properties,
                             TokenCounter tokenCounter,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.webClient      = webClient;
        this.properties     = properties;
        this.tokenCounter   = tokenCounter;
        this.objectMapper   = objectMapper != null ? objectMapper : new ObjectMapper();
        this.successCounter = meterRegistry.counter("gateway.llm.requests", "provider", "openai", "result", "success");
        this.errorCounter   = meterRegistry.counter("gateway.llm.requests", "provider", "openai", "result", "error");
        this.latencyTimer   = Timer.builder("gateway.llm.latency")
                .tag("provider", "openai")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    @CircuitBreaker(name = "openAi", fallbackMethod = "fallbackGenerate")
    public GenerateResponse generate(String requestId, String userId, String prompt, String model) {
        GatewayProperties.LlmProperties llm = properties.getLlm();
        String resolvedModel = (model != null && !model.isBlank()) ? model : llm.getOpenaiDefaultModel();

        log.info("[OPENAI] requestId={} userId={} model={} promptLen={}", requestId, userId, resolvedModel, prompt.length());

        OpenAiChatRequest chatRequest = OpenAiChatRequest.builder()
                .model(resolvedModel)
                .messages(List.of(
                        OpenAiChatRequest.Message.system(SYSTEM_PROMPT),
                        OpenAiChatRequest.Message.user(prompt)
                ))
                .maxTokens(llm.getOpenaiMaxTokens())
                .temperature(llm.getOpenaiTemperature())
                .build();

        long startMs = System.currentTimeMillis();

        OpenAiChatResponse chatResponse = latencyTimer.record(() ->
                callWithRetry(chatRequest, requestId, llm.getMaxRetries(), llm.getRetryBackoffMs())
        );

        long latencyMs = System.currentTimeMillis() - startMs;

        int totalTokens = (chatResponse.getUsage() != null)
                ? chatResponse.getUsage().getTotalTokens()
                : tokenCounter.estimateTokens(prompt) + tokenCounter.estimateTokens(chatResponse.firstChoiceText());

        String responseText = chatResponse.firstChoiceText();
        String usedModel    = chatResponse.getModel() != null ? chatResponse.getModel() : resolvedModel;

        log.info("[OPENAI] requestId={} userId={} model={} tokens={} latencyMs={}", requestId, userId, usedModel, totalTokens, latencyMs);
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
    @CircuitBreaker(name = "openAi", fallbackMethod = "fallbackStream")
    public Flux<String> stream(String requestId, String userId, String prompt, String model) {
        GatewayProperties.LlmProperties llm = properties.getLlm();
        String resolvedModel = (model != null && !model.isBlank()) ? model : llm.getOpenaiDefaultModel();

        log.info("[OPENAI-STREAM] START requestId={} userId={} model={}", requestId, userId, resolvedModel);

        OpenAiChatRequest chatRequest = OpenAiChatRequest.builder()
                .model(resolvedModel)
                .messages(List.of(
                        OpenAiChatRequest.Message.system(SYSTEM_PROMPT),
                        OpenAiChatRequest.Message.user(prompt)
                ))
                .maxTokens(llm.getOpenaiMaxTokens())
                .temperature(llm.getOpenaiTemperature())
                .stream(true)
                .build();

        return webClient.post()
                .uri(COMPLETIONS_PATH)
                .bodyValue(chatRequest)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new LlmBackendException(
                                        "OpenAI streaming request failed (" + response.statusCode().value() + "): " + body,
                                        null))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(data -> data != null && !data.isBlank() && !"[DONE]".equals(data.trim()))
                .<String>handle((data, sink) -> {
                    if (data.equals("[DONE]") || data.isEmpty()) {
                        return;
                    }
                    try {
                        JsonNode root = objectMapper.readTree(data);
                        JsonNode choices = root.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).path("delta");
                            String content = delta.path("content").asText(null);
                            if (content != null && !content.isEmpty()) {
                                sink.next(content);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[OPENAI-STREAM] Invalid SSE data requestId={} error={}", requestId, e.getMessage());
                    }
                })
                .doOnComplete(() -> {
                    successCounter.increment();
                    log.info("[OPENAI-STREAM] COMPLETE requestId={}", requestId);
                })
                .doOnError(err -> {
                    errorCounter.increment();
                    log.error("[OPENAI-STREAM] ERROR requestId={} error={}", requestId, err.getMessage());
                });
    }

    @SuppressWarnings("unused")
    private GenerateResponse fallbackGenerate(String requestId, String userId, String prompt,
                                              String model, Throwable cause) {
        errorCounter.increment();
        log.warn("[OPENAI] Circuit breaker fallback requestId={} userId={} cause={}",
                requestId, userId, cause.toString());
        throw new ProviderUnavailableException("OpenAI provider unavailable", cause);
    }

    @SuppressWarnings("unused")
    private Flux<String> fallbackStream(String requestId, String userId, String prompt,
                                         String model, Throwable cause) {
        errorCounter.increment();
        log.warn("[OPENAI-STREAM] Circuit breaker fallback requestId={} userId={} cause={}",
                requestId, userId, cause.toString());
        return Flux.error(new ProviderUnavailableException("OpenAI provider unavailable", cause));
    }

    private OpenAiChatResponse callWithRetry(OpenAiChatRequest request,
                                              String requestId,
                                              int maxRetries,
                                              int backoffMs) {
        try {
            return webClient.post()
                    .uri(COMPLETIONS_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.TOO_MANY_REQUESTS.value(), resp ->
                            resp.bodyToMono(String.class).flatMap(body -> {
                                log.warn("[OPENAI] 429 rate limited requestId={}", requestId);
                                return Mono.error(new LlmBackendException("OpenAI rate limit exceeded (429): " + body, null));
                            })
                    )
                    .onStatus(status -> status.is4xxClientError(), resp ->
                            resp.bodyToMono(OpenAiErrorResponse.class).flatMap(err -> {
                                String msg = err != null ? err.message() : "Client error";
                                log.error("[OPENAI] 4xx error requestId={} message={}", requestId, msg);
                                return Mono.error(new LlmBackendException("OpenAI API error: " + msg, null));
                            })
                    )
                    .onStatus(status -> status.is5xxServerError(), resp ->
                            resp.bodyToMono(String.class).flatMap(body -> {
                                log.warn("[OPENAI] 5xx error requestId={}, will retry: {}", requestId, body);
                                return Mono.error(new WebClientResponseException(
                                        500, "OpenAI server error", null, null, null));
                            })
                    )
                    .bodyToMono(OpenAiChatResponse.class)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(backoffMs))
                            .filter(ex -> !(ex instanceof LlmBackendException))
                            .onRetryExhaustedThrow((spec, signal) ->
                                    new LlmBackendException("OpenAI unavailable after " + maxRetries + " retries", signal.failure())
                            )
                    )
                    .block();
        } catch (LlmBackendException e) {
            errorCounter.increment();
            throw e;
        } catch (Exception e) {
            errorCounter.increment();
            log.error("[OPENAI] Unexpected error requestId={}: {}", requestId, e.getMessage(), e);
            throw new LlmBackendException("OpenAI request failed: " + e.getMessage(), e);
        }
    }
}
