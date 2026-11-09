package com.tokengateway.exception;

/**
 * Raised when an LLM provider is protected by an open circuit or its fallback
 * is invoked after repeated failures or slow calls.
 */
public class ProviderUnavailableException extends LlmBackendException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
