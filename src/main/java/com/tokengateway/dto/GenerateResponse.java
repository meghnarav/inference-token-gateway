package com.tokengateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GenerateResponse {

    @JsonProperty("requestId")
    private String requestId;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("response")
    private String response;

    @JsonProperty("tokensConsumed")
    private int tokensConsumed;

    @JsonProperty("cacheHit")
    private boolean cacheHit;

    @JsonProperty("latencyMs")
    private long latencyMs;

    @JsonProperty("model")
    private String model;

    @JsonProperty("timestamp")
    private Instant timestamp;

    /** Remaining tokens in the current rate-limit window */
    @JsonProperty("remainingTokensThisMinute")
    private long remainingTokensThisMinute;
}
