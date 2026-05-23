package com.tokengateway.util;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

/**
 * Generates deterministic, stable hash keys from prompt text.
 * Used for both the Redis response cache and the PostgreSQL usage_events index.
 *
 * SHA-256 is used over MD5/SHA-1 for collision resistance.
 * The hash is truncated to 16 hex chars for compact storage while
 * maintaining effectively zero collision probability at gateway scale.
 */
@Component
public class PromptHasher {

    /**
     * Produces a deterministic 64-char hex hash of the normalized prompt.
     * Normalization: lowercase + collapse whitespace, ensuring
     * "Hello World" and "hello  world" map to the same cache key.
     */
    public String hash(String prompt) {
        String normalized = prompt.trim().toLowerCase().replaceAll("\\s+", " ");
        return DigestUtils.sha256Hex(normalized);
    }

    /**
     * Full Redis cache key including prefix, for use in cache operations.
     */
    public String cacheKey(String keyPrefix, String prompt) {
        return keyPrefix + hash(prompt);
    }
}
