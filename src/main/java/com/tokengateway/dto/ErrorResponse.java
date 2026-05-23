package com.tokengateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    @JsonProperty("requestId")
    private String requestId;

    @JsonProperty("error")
    private String error;

    @JsonProperty("message")
    private String message;

    @JsonProperty("statusCode")
    private int statusCode;

    @JsonProperty("timestamp")
    private Instant timestamp;

    /** Only present for rate-limit errors */
    @JsonProperty("retryAfterSeconds")
    private Long retryAfterSeconds;

    /** Remaining token budget (for rate-limit errors) */
    @JsonProperty("remainingTokens")
    private Long remainingTokens;

    public static ErrorResponse of(String requestId, String error, String message, int statusCode) {
        return ErrorResponse.builder()
                .requestId(requestId)
                .error(error)
                .message(message)
                .statusCode(statusCode)
                .timestamp(Instant.now())
                .build();
    }
}
