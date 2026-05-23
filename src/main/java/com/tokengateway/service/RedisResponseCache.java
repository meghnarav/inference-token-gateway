package com.tokengateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.util.PromptHasher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis-backed response cache for LLM inference results.
 *
 * Cache Key Strategy:
 * - Key = "{prefix}{sha256(normalized_prompt)}"
 * - Normalization: lowercase + collapse whitespace
 * - This ensures "Hello World" and "hello  world" share a cache entry
 *
 * Cache Hit Behavior:
 * - On hit: skip LLM call, return cached response with cacheHit=true
 * - Token consumption is reduced to prompt-tokens only (no generation cost)
 * - Latency is reduced to near-zero
 *
 * Failure Handling:
 * - Any Redis exception returns Optional.empty() (cache miss)
 * - Cache failures never propagate to the caller
 * - All failures are logged and counted in metrics
 */
@Service
@Slf4j
public class RedisResponseCache implements ResponseCache {

    private final RedisTemplate<String, Object> redisTemplate;
    private final GatewayProperties properties;
    private final PromptHasher promptHasher;
    private final ObjectMapper objectMapper;
    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter errorCounter;

    public RedisResponseCache(RedisTemplate<String, Object> redisTemplate,
                              GatewayProperties properties,
                              PromptHasher promptHasher,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.promptHasher = promptHasher;
        this.objectMapper = objectMapper;
        this.hitCounter  = meterRegistry.counter("gateway.cache.operations", "result", "hit");
        this.missCounter = meterRegistry.counter("gateway.cache.operations", "result", "miss");
        this.errorCounter = meterRegistry.counter("gateway.cache.operations", "result", "error");
    }

    @Override
    public Optional<GenerateResponse> get(String prompt) {
        // Skip cache for very long prompts (memory conservation)
        if (prompt.length() > properties.getCache().getMaxCacheablePromptLength()) {
            log.debug("[CACHE] Prompt too long for cache (length={}), bypassing", prompt.length());
            return Optional.empty();
        }

        String key = promptHasher.cacheKey(properties.getCache().getKeyPrefix(), prompt);
        try {
            Object raw = redisTemplate.opsForValue().get(key);
            if (raw == null) {
                missCounter.increment();
                log.debug("[CACHE] MISS key={}", key.substring(key.length() - 8)); // log only last 8 chars
                return Optional.empty();
            }

            GenerateResponse response = deserialize(raw);
            hitCounter.increment();
            log.info("[CACHE] HIT key={}...{}", key.substring(0, 8), key.substring(key.length() - 8));
            return Optional.of(response);

        } catch (Exception e) {
            errorCounter.increment();
            log.warn("[CACHE] GET failed for key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String prompt, GenerateResponse response) {
        if (prompt.length() > properties.getCache().getMaxCacheablePromptLength()) {
            return;
        }

        String key = promptHasher.cacheKey(properties.getCache().getKeyPrefix(), prompt);
        try {
            Duration ttl = Duration.ofSeconds(properties.getCache().getResponseTtlSeconds());
            String serialized = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(key, serialized, ttl);
            log.debug("[CACHE] PUT key={}...{} ttl={}s", key.substring(0, 8), key.substring(key.length() - 8), ttl.getSeconds());
        } catch (Exception e) {
            errorCounter.increment();
            log.warn("[CACHE] PUT failed for key={}: {}", key, e.getMessage());
            // Cache write failure is non-fatal — request proceeds normally
        }
    }

    @Override
    public void evict(String prompt) {
        String key = promptHasher.cacheKey(properties.getCache().getKeyPrefix(), prompt);
        try {
            redisTemplate.delete(key);
            log.debug("[CACHE] EVICT key={}", key);
        } catch (Exception e) {
            log.warn("[CACHE] EVICT failed for key={}: {}", key, e.getMessage());
        }
    }

    private GenerateResponse deserialize(Object raw) throws JsonProcessingException {
        if (raw instanceof GenerateResponse r) {
            return r;
        }
        // Deserialize from JSON string (most common case with StringSerializer)
        return objectMapper.readValue(raw.toString(), GenerateResponse.class);
    }
}
