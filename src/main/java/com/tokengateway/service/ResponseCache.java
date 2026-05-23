package com.tokengateway.service;

import com.tokengateway.dto.GenerateResponse;

import java.util.Optional;

/**
 * Contract for prompt-response caching.
 * Implementors must be thread-safe and resilient to cache backend failures.
 */
public interface ResponseCache {

    /**
     * Look up a cached response for the given prompt.
     *
     * @param prompt The raw user prompt
     * @return Optional containing the cached response, or empty on miss/error
     */
    Optional<GenerateResponse> get(String prompt);

    /**
     * Store a response in the cache.
     *
     * @param prompt   The raw user prompt (used to derive cache key)
     * @param response The response to cache
     */
    void put(String prompt, GenerateResponse response);

    /**
     * Evict a cached entry (e.g., after detecting stale content).
     *
     * @param prompt The raw user prompt
     */
    void evict(String prompt);
}
