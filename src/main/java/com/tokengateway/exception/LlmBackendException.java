package com.tokengateway.exception;

public class LlmBackendException extends RuntimeException {
    public LlmBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
