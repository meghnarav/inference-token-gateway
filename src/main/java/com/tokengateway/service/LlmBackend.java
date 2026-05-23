package com.tokengateway.service;

import com.tokengateway.dto.GenerateResponse;

/**
 * Abstraction over the LLM inference backend.
 *
 * Design intent: This interface cleanly separates the gateway's
 * rate-limiting and caching logic from the actual model invocation.
 * Swapping SimulatedLlmBackend for OpenAiLlmBackend, AnthropicLlmBackend,
 * or a vLLM endpoint requires zero changes to GatewayService.
 */
public interface LlmBackend {

    /**
     * Generates a response for the given prompt.
     *
     * @param requestId Correlation ID for tracing
     * @param userId    Requesting user
     * @param prompt    The input prompt
     * @param model     Model identifier hint (implementation may ignore)
     * @return Partially-populated GenerateResponse (without rate-limit metadata)
     * @throws com.tokengateway.exception.LlmBackendException on inference failure
     */
    GenerateResponse generate(String requestId, String userId, String prompt, String model);
}
