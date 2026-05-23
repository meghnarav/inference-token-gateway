package com.tokengateway.exception;

import com.tokengateway.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Centralised exception → HTTP response mapping.
 *
 * Keeps controller code clean by handling all error cases here.
 * All handlers produce consistent ErrorResponse JSON with correlation IDs.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        log.warn("[ERROR] RATE_LIMIT_EXCEEDED userId={} usage={} limit={} retryAfter={}s",
                ex.getUserId(), ex.getCurrentUsage(), ex.getLimit(), ex.getRetryAfterSeconds());

        ErrorResponse body = ErrorResponse.builder()
                .requestId(MDC.get("requestId"))
                .error("RATE_LIMIT_EXCEEDED")
                .message(String.format(
                        "Token limit of %d per minute exceeded. Current usage: %d tokens. " +
                        "Retry after %d seconds.",
                        ex.getLimit(), ex.getCurrentUsage(), ex.getRetryAfterSeconds()))
                .statusCode(429)
                .retryAfterSeconds(ex.getRetryAfterSeconds())
                .remainingTokens(0L)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .header("X-RateLimit-Limit", String.valueOf(ex.getLimit()))
                .header("X-RateLimit-Remaining", "0")
                .body(body);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.warn("[ERROR] USER_NOT_FOUND: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(MDC.get("requestId"), "USER_NOT_FOUND", ex.getMessage(), 404));
    }

    @ExceptionHandler(LlmBackendException.class)
    public ResponseEntity<ErrorResponse> handleLlmError(LlmBackendException ex) {
        log.error("[ERROR] LLM_BACKEND_ERROR: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(MDC.get("requestId"), "LLM_BACKEND_ERROR",
                        "Inference backend is temporarily unavailable. Please retry.", 502));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        log.warn("[ERROR] VALIDATION_ERROR: {}", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(MDC.get("requestId"), "VALIDATION_ERROR", details, 400));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("[ERROR] INTERNAL_ERROR: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(MDC.get("requestId"), "INTERNAL_ERROR",
                        "An unexpected error occurred. Please contact support.", 500));
    }
}
