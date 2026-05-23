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
    }

    @Data
    public static class LlmProperties {
        private int minLatencyMs = 100;
        private int maxLatencyMs = 800;
        private double tokensPerWord = 1.3;
        private int minResponseWords = 20;
        private int maxResponseWords = 150;
    }

    @Data
    public static class IdempotencyProperties {
        private int ttlSeconds = 86_400;
    }
}
