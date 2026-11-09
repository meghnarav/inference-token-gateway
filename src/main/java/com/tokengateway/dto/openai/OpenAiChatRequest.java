package com.tokengateway.dto.openai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * OpenAI Chat Completions API request body.
 * Reference: https://platform.openai.com/docs/api-reference/chat/create
 */
@Data
@Builder
public class OpenAiChatRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<Message> messages;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("temperature")
    private Double temperature;

    /** Stream must be false — we consume the full response synchronously. */
    @JsonProperty("stream")
    @Builder.Default
    private boolean stream = false;

    @Data
    @Builder
    public static class Message {
        @JsonProperty("role")
        private String role;

        @JsonProperty("content")
        private String content;

        public static Message user(String content) {
            return Message.builder().role("user").content(content).build();
        }

        public static Message system(String content) {
            return Message.builder().role("system").content(content).build();
        }
    }
}
