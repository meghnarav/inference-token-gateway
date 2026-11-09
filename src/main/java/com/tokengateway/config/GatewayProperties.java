package com.tokengateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gateway")
@Data
public class GatewayProperties {

    private RateLimitProperties rateLimit = new RateLimitProperties();
    private CacheProperties cache = new CacheProperties();
    private LlmProperties llm = new LlmProperties();
    private KafkaProperties kafka = new KafkaProperties();
    private IdempotencyProperties idempotency = new IdempotencyProperties();

    @Data
    public static class RateLimitProperties {
        private long tokensPerMinute = 10_000;
        private int windowSeconds = 60;
        private int keyTtlBufferSeconds = 10;
        private boolean enabled = true;
    }

    @Data
    public static class CacheProperties {
        private long responseTtlSeconds = 300;
        private int maxCacheablePromptLength = 2000;
        private String keyPrefix = "resp:v1:";
        /** Minimum cosine similarity threshold for semantic cache hits (default 0.95) */
        private double similarityThreshold = 0.95;
        /** Whether semantic cache lookup is enabled */
        private boolean semanticEnabled = true;
        /** Vector dimension for embeddings (384 for all-MiniLM-L6-v2) */
        private int vectorDimension = 384;
    }

    @Data
    public static class LlmProperties {
        // ── Provider selection ──────────────────────────────────────────
        /** Active backend: "openai", "ollama", or "simulated" */
        private String provider = "simulated";

        // ── Simulated backend (legacy) ──────────────────────────────────
        private int minLatencyMs = 100;
        private int maxLatencyMs = 800;
        private double tokensPerWord = 1.3;
        private int minResponseWords = 20;
        private int maxResponseWords = 150;

        // ── OpenAI ─────────────────────────────────────────────────────
        /** API key — set via OPENAI_API_KEY env var */
        private String openaiApiKey = "";
        /** Base URL (override for proxies / Azure OpenAI endpoints) */
        private String openaiBaseUrl = "https://api.openai.com/v1";
        /** Default model when none specified in the request */
        private String openaiDefaultModel = "gpt-4o-mini";
        /** Max tokens to request from OpenAI */
        private int openaiMaxTokens = 1024;
        /** Temperature (0.0–2.0) */
        private double openaiTemperature = 0.7;

        // ── Ollama ─────────────────────────────────────────────────────
        /** Ollama server base URL */
        private String ollamaBaseUrl = "http://localhost:11434";
        /** Default model when none specified */
        private String ollamaDefaultModel = "llama3.2";

        // ── Shared HTTP client settings ─────────────────────────────────
        /** Connect timeout in milliseconds */
        private int connectTimeoutMs = 5_000;
        /** Read timeout in milliseconds (must be large enough for slow models) */
        private int readTimeoutMs = 120_000;
        /** Max retries on transient failures (5xx, timeout) */
        private int maxRetries = 2;
        /** Initial retry backoff in milliseconds */
        private int retryBackoffMs = 500;
    }

    @Data
    public static class IdempotencyProperties {
        private int ttlSeconds = 86_400;
    }

    @Data
    public static class KafkaProperties {
        private String usageTopic = "token-usage-events";
    }
}
