package com.tokengateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data payload for Server-Sent Events (SSE) streaming chunk responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamChunk {

    @JsonProperty("requestId")
    private String requestId;

    @JsonProperty("content")
    private String content;

    @JsonProperty("tokensSoFar")
    private int tokensSoFar;

    @JsonProperty("done")
    private boolean done;

    @JsonProperty("cacheHit")
    private boolean cacheHit;

    @JsonProperty("finishReason")
    private String finishReason;

    @JsonProperty("error")
    private String error;
}
