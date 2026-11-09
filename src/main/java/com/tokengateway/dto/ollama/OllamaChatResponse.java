package com.tokengateway.dto.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Ollama /api/chat response body (non-streaming).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaChatResponse {

    @JsonProperty("model")
    private String model;

    @JsonProperty("message")
    private OllamaChatRequest.Message message;

    @JsonProperty("done")
    private boolean done;

    /** Nanoseconds of prompt evaluation time (Ollama-reported). */
    @JsonProperty("prompt_eval_duration")
    private long promptEvalDuration;

    /** Nanoseconds of token generation time. */
    @JsonProperty("eval_duration")
    private long evalDuration;

    /** Number of prompt tokens evaluated. */
    @JsonProperty("prompt_eval_count")
    private int promptEvalCount;

    /** Number of tokens generated. */
    @JsonProperty("eval_count")
    private int evalCount;

    /** Convenience: total token count reported by Ollama. */
    public int totalTokens() {
        return promptEvalCount + evalCount;
    }

    /** Convenience: response text content. */
    public String content() {
        return message != null ? message.getContent() : "";
    }
}
