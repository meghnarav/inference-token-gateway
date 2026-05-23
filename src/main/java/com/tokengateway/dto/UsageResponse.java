package com.tokengateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class UsageResponse {

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("totalTokensConsumed")
    private long totalTokensConsumed;

    @JsonProperty("totalRequests")
    private int totalRequests;

    @JsonProperty("cacheHitCount")
    private int cacheHitCount;

    @JsonProperty("cacheHitRate")
    private double cacheHitRate;

    /** Tokens used in the current sliding window (from Redis) */
    @JsonProperty("tokensUsedThisMinute")
    private long tokensUsedThisMinute;

    @JsonProperty("tokensPerMinuteLimit")
    private long tokensPerMinuteLimit;

    @JsonProperty("dailyBreakdown")
    private List<DailySummary> dailyBreakdown;

    @JsonProperty("queriedAt")
    private Instant queriedAt;

    @Data
    @Builder
    public static class DailySummary {
        @JsonProperty("date")
        private String date;

        @JsonProperty("tokensConsumed")
        private long tokensConsumed;

        @JsonProperty("requests")
        private int requests;

        @JsonProperty("cacheHits")
        private int cacheHits;
    }
}
