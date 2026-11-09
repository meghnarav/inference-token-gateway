package com.tokengateway.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Configuration for LangChain4j Semantic Caching and Vector Store.
 *
 * Components:
 *  - EmbeddingModel: all-MiniLM-L6-v2 running in-process via ONNX Runtime (384-dimensional).
 *  - EmbeddingStore: PgVectorEmbeddingStore connecting to PostgreSQL with pgvector extension.
 *    Gracefully falls back to InMemoryEmbeddingStore if pgvector extension is not available.
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class SemanticCacheConfig {

    private final DataSource dataSource;
    private final GatewayProperties properties;

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("[SEMANTIC-CACHE] Initializing all-MiniLM-L6-v2 in-process embedding model");
        return new AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
        int dimension = properties.getCache().getVectorDimension();
        try {
            log.info("[SEMANTIC-CACHE] Attempting to initialize PgVectorEmbeddingStore (dimension={})", dimension);
            PgVectorEmbeddingStore pgStore = PgVectorEmbeddingStore.datasourceBuilder()
                    .datasource(dataSource)
                    .table("semantic_cache")
                    .dimension(dimension)
                    .useIndex(true)
                    .createTable(true)
                    .build();
            log.info("[SEMANTIC-CACHE] PgVectorEmbeddingStore initialized successfully");
            return pgStore;
        } catch (Exception e) {
            log.warn("[SEMANTIC-CACHE] Failed to initialize PgVectorEmbeddingStore ({}), falling back to InMemoryEmbeddingStore",
                    e.getMessage());
            return new InMemoryEmbeddingStore<>();
        }
    }
}
