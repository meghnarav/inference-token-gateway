package com.tokengateway.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Configures WebClient beans for the OpenAI and Ollama LLM backends.
 *
 * Each bean is scoped to its own base URL and timeout profile:
 * - openAiWebClient  → https://api.openai.com/v1 with Bearer-token auth
 * - ollamaWebClient  → http://localhost:11434 (no auth, local service)
 *
 * Both share the same Netty HttpClient factory approach so timeouts are
 * configured at the TCP layer (connect + read), not just at the Spring layer.
 */
@Configuration
@RequiredArgsConstructor
public class LlmClientConfig {

    private final GatewayProperties properties;

    @Bean("openAiWebClient")
    public WebClient openAiWebClient() {
        GatewayProperties.LlmProperties llm = properties.getLlm();
        HttpClient httpClient = buildHttpClient(llm.getConnectTimeoutMs(), llm.getReadTimeoutMs());
        return WebClient.builder()
                .baseUrl(llm.getOpenaiBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + llm.getOpenaiApiKey())
                .filter(logRequest())
                .build();
    }

    @Bean("ollamaWebClient")
    public WebClient ollamaWebClient() {
        GatewayProperties.LlmProperties llm = properties.getLlm();
        HttpClient httpClient = buildHttpClient(llm.getConnectTimeoutMs(), llm.getReadTimeoutMs());
        return WebClient.builder()
                .baseUrl(llm.getOllamaBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .filter(logRequest())
                .build();
    }

    private HttpClient buildHttpClient(int connectTimeoutMs, int readTimeoutMs) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)));
    }

    /** Logs outbound LLM requests at TRACE level for debugging. */
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            // Intentionally not logging body to avoid leaking API keys or PII
            org.slf4j.LoggerFactory.getLogger(LlmClientConfig.class)
                    .trace("[LLM-CLIENT] {} {}", req.method(), req.url());
            return Mono.just(req);
        });
    }
}
