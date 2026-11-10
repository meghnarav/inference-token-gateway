package com.tokengateway;

import com.tokengateway.config.GatewayProperties;
import com.tokengateway.dto.GenerateResponse;
import com.tokengateway.service.ResponseCache;
import com.tokengateway.service.SemanticResponseCache;
import com.tokengateway.util.TokenCounter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticResponseCacheTest {

    @Mock
    private ResponseCache redisCache;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    private GatewayProperties properties;
    private TokenCounter tokenCounter;
    private SemanticResponseCache semanticCache;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getCache().setSemanticEnabled(true);
        properties.getCache().setSimilarityThreshold(0.95);
        tokenCounter = new TokenCounter();

        semanticCache = new SemanticResponseCache(
                redisCache,
                embeddingModel,
                embeddingStore,
                properties,
                tokenCounter,
                new SimpleMeterRegistry()
        );
    }

    @Test
    @DisplayName("L1 Exact Match: returns from Redis without invoking vector search")
    void testExactMatchHit() {
        String prompt = "What is rate limiting?";
        GenerateResponse cachedResponse = GenerateResponse.builder()
                .response("Rate limiting controls traffic rate.")
                .tokensConsumed(25)
                .cacheHit(true)
                .build();

        when(redisCache.get(prompt)).thenReturn(Optional.of(cachedResponse));

        Optional<GenerateResponse> result = semanticCache.get(prompt);

        assertThat(result).isPresent();
        assertThat(result.get().getResponse()).isEqualTo("Rate limiting controls traffic rate.");
        verifyNoInteractions(embeddingModel);
        verifyNoInteractions(embeddingStore);
    }

    @Test
    @DisplayName("L2 Semantic Match: returns cached response when similarity >= 0.95")
    void testSemanticMatchHitAboveThreshold() {
        String prompt = "Explain rate limiting in APIs";
        float[] vector = new float[384];
        Embedding dummyEmbedding = new Embedding(vector);

        when(redisCache.get(prompt)).thenReturn(Optional.empty());
        when(embeddingModel.embed(prompt)).thenReturn(Response.from(dummyEmbedding));

        Metadata meta = new Metadata();
        meta.put("response", "Rate limiting protects APIs from overload.");
        meta.put("model", "gpt-4o-mini");
        meta.put("tokensConsumed", 30);
        TextSegment matchedSegment = TextSegment.from("What is API rate limiting?", meta);

        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.965, "match-1", dummyEmbedding, matchedSegment);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new dev.langchain4j.store.embedding.EmbeddingSearchResult<>(List.of(match)));

        Optional<GenerateResponse> result = semanticCache.get(prompt);

        assertThat(result).isPresent();
        assertThat(result.get().getResponse()).isEqualTo("Rate limiting protects APIs from overload.");
        assertThat(result.get().isCacheHit()).isTrue();
    }

    @Test
    @DisplayName("L2 Semantic Miss: returns empty when similarity is below 0.95 threshold")
    void testSemanticMissBelowThreshold() {
        String prompt = "How do rockets work?";
        float[] vector = new float[384];
        Embedding dummyEmbedding = new Embedding(vector);

        when(redisCache.get(prompt)).thenReturn(Optional.empty());
        when(embeddingModel.embed(prompt)).thenReturn(Response.from(dummyEmbedding));
        TextSegment matchedSegment = TextSegment.from("A rocket question", new Metadata());
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.90, "match-1", dummyEmbedding, matchedSegment);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new dev.langchain4j.store.embedding.EmbeddingSearchResult<>(List.of(match)));

        Optional<GenerateResponse> result = semanticCache.get(prompt);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("L2 Semantic Miss: ignores expired vector entries")
    void testSemanticMissExpiredEntry() {
        String prompt = "How do rockets work?";
        Embedding embedding = new Embedding(new float[384]);
        when(redisCache.get(prompt)).thenReturn(Optional.empty());
        when(embeddingModel.embed(prompt)).thenReturn(Response.from(embedding));

        Metadata metadata = new Metadata();
        metadata.put("response", "Expired response");
        metadata.put("expiresAtEpochMs", System.currentTimeMillis() - 1);
        TextSegment segment = TextSegment.from(prompt, metadata);
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new dev.langchain4j.store.embedding.EmbeddingSearchResult<>(
                        List.of(new EmbeddingMatch<>(0.99, "expired", embedding, segment))));

        assertThat(semanticCache.get(prompt)).isEmpty();
    }

    @Test
    @DisplayName("Put stores in both L1 Redis and L2 Vector DB")
    void testPutStoresBothLayers() {
        String prompt = "What is distributed caching?";
        GenerateResponse response = GenerateResponse.builder()
                .response("Caching across multiple nodes.")
                .tokensConsumed(20)
                .model("gpt-4o-mini")
                .requestId("req-123")
                .build();

        float[] vector = new float[384];
        Embedding dummyEmbedding = new Embedding(vector);
        when(embeddingModel.embed(prompt)).thenReturn(Response.from(dummyEmbedding));

        semanticCache.put(prompt, response);

        verify(redisCache).put(prompt, response);
        verify(embeddingStore).add(eq(dummyEmbedding), any(TextSegment.class));
    }
}
