package com.tokengateway.service;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.util.TokenCounter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Two-tiered Semantic Response Cache combining:
 *  1. L1 Exact Match: Redis key-value cache (instant, zero compute)
 *  2. L2 Semantic Match: LangChain4j + Vector DB (pgvector / in-memory)
 *     Uses dense vector cosine similarity with a configurable threshold (default 0.95).
 */
@Service("semanticResponseCache")
@Primary
@Slf4j
public class SemanticResponseCache implements ResponseCache {

    private final ResponseCache exactCache;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final GatewayProperties properties;
    private final TokenCounter tokenCounter;

    private final Counter exactHitCounter;
    private final Counter semanticHitCounter;
    private final Counter missCounter;
    private final Counter cacheHitMetric;
    private final Counter cacheMissMetric;

    public SemanticResponseCache(@org.springframework.beans.factory.annotation.Qualifier("redisResponseCache") ResponseCache exactCache,
                                 EmbeddingModel embeddingModel,
                                 EmbeddingStore<TextSegment> embeddingStore,
                                 GatewayProperties properties,
                                 TokenCounter tokenCounter,
                                 MeterRegistry meterRegistry) {
        this.exactCache          = exactCache;
        this.embeddingModel      = embeddingModel;
        this.embeddingStore      = embeddingStore;
        this.properties          = properties;
        this.tokenCounter        = tokenCounter;
        this.exactHitCounter     = meterRegistry.counter("gateway.cache.hits", "tier", "exact_redis");
        this.semanticHitCounter  = meterRegistry.counter("gateway.cache.hits", "tier", "semantic_vector");
        this.missCounter         = meterRegistry.counter("gateway.cache.operations", "result", "total_miss");
        this.cacheHitMetric      = meterRegistry.counter("gateway.cache.requests", "outcome", "hit");
        this.cacheMissMetric     = meterRegistry.counter("gateway.cache.requests", "outcome", "miss");
    }

    @Override
    public Optional<GenerateResponse> get(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return Optional.empty();
        }

        // Tier 1: Check exact match in Redis (L1 fast path)
        Optional<GenerateResponse> exactMatch = exactCache.get(prompt);
        if (exactMatch.isPresent()) {
            exactHitCounter.increment();
            cacheHitMetric.increment();
            log.info("[CACHE] L1 EXACT_HIT in Redis for prompt prefix='{}...'",
                    prompt.substring(0, Math.min(prompt.length(), 25)));
            return exactMatch;
        }

        // Tier 2: Semantic vector search via LangChain4j + Vector DB
        if (!properties.getCache().isSemanticEnabled()) {
            missCounter.increment();
            cacheMissMetric.increment();
            return Optional.empty();
        }
        if (prompt.length() > properties.getCache().getMaxCacheablePromptLength()) {
            missCounter.increment();
            cacheMissMetric.increment();
            return Optional.empty();
        }

        try {
            double threshold = properties.getCache().getSimilarityThreshold();
            long startMs = System.currentTimeMillis();

            // Embed prompt into vector space
            Embedding queryEmbedding = embeddingModel.embed(prompt).content();

            // Search for nearest vector with cosine similarity >= threshold (default 0.95)
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(1)
                            .minScore(threshold)
                            .build())
                    .matches();

            long searchLatencyMs = System.currentTimeMillis() - startMs;

            if (!matches.isEmpty()) {
                EmbeddingMatch<TextSegment> match = matches.get(0);
                double similarity = match.score();

                TextSegment segment = match.embedded();
                Metadata meta = (segment != null) ? segment.metadata() : new Metadata();
                Long expiresAt = meta.getLong("expiresAtEpochMs");

                String cachedResponse = meta.getString("response");
                if (similarity >= threshold
                        && (expiresAt == null || expiresAt > System.currentTimeMillis())
                        && cachedResponse != null
                        && !cachedResponse.isBlank()) {
                    String model = meta.getString("model");
                    Integer tokens = meta.getInteger("tokensConsumed");
                    int tokensConsumed = (tokens != null) ? tokens : tokenCounter.estimateTokens(cachedResponse);

                    semanticHitCounter.increment();
                    cacheHitMetric.increment();
                    log.info("[SEMANTIC-CACHE] L2 SEMANTIC_HIT similarity={}/{} (search={}ms) for prompt='{}...'",
                            String.format("%.4f", similarity), threshold, searchLatencyMs,
                            prompt.substring(0, Math.min(prompt.length(), 30)));

                    GenerateResponse response = GenerateResponse.builder()
                            .requestId("sem-" + System.currentTimeMillis())
                            .response(cachedResponse)
                            .tokensConsumed(tokensConsumed)
                            .cacheHit(true)
                            .model(model != null ? model : "semantic-cached")
                            .timestamp(Instant.now())
                            .build();

                    return Optional.of(response);
                }
            }

            log.debug("[SEMANTIC-CACHE] L2 MISS (search={}ms) for prompt length={}", searchLatencyMs, prompt.length());
        } catch (Exception e) {
            log.warn("[SEMANTIC-CACHE] Vector search failed: {}", e.getMessage());
        }

        missCounter.increment();
        cacheMissMetric.increment();
        return Optional.empty();
    }

    @Override
    public void put(String prompt, GenerateResponse response) {
        if (prompt == null || response == null || response.getResponse() == null) {
            return;
        }

        // 1. Store in L1 Redis cache
        try {
            exactCache.put(prompt, response);
        } catch (Exception e) {
            log.warn("[CACHE] Redis PUT failed: {}", e.getMessage());
        }

        // 2. Store in L2 Semantic Vector DB
        if (!properties.getCache().isSemanticEnabled()) {
            return;
        }

        if (prompt.length() > properties.getCache().getMaxCacheablePromptLength()) {
            return;
        }

        try {
            Embedding embedding = embeddingModel.embed(prompt).content();

            Metadata metadata = new Metadata();
            metadata.put("response", response.getResponse());
            metadata.put("model", response.getModel() != null ? response.getModel() : "unknown");
            metadata.put("tokensConsumed", response.getTokensConsumed());
            metadata.put("requestId", response.getRequestId() != null ? response.getRequestId() : "");
            metadata.put("expiresAtEpochMs",
                    System.currentTimeMillis() + properties.getCache().getResponseTtlSeconds() * 1_000L);

            TextSegment textSegment = TextSegment.from(prompt, metadata);
            embeddingStore.add(embedding, textSegment);

            log.info("[SEMANTIC-CACHE] Stored embedding in vector store for prompt length={}", prompt.length());
        } catch (Exception e) {
            log.warn("[SEMANTIC-CACHE] Failed to store embedding in vector store: {}", e.getMessage());
        }
    }

    @Override
    public void evict(String prompt) {
        exactCache.evict(prompt);
        if (!properties.getCache().isSemanticEnabled() || prompt == null || prompt.isBlank()) {
            return;
        }
        try {
            Embedding queryEmbedding = embeddingModel.embed(prompt).content();
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(10)
                            .minScore(0.0)
                            .build())
                    .matches();
            matches.stream()
                    .filter(match -> match.embedded() != null
                            && prompt.equals(match.embedded().text()))
                    .map(EmbeddingMatch::embeddingId)
                    .forEach(embeddingStore::remove);
        } catch (Exception e) {
            log.warn("[SEMANTIC-CACHE] Vector eviction failed: {}", e.getMessage());
        }
    }
}
