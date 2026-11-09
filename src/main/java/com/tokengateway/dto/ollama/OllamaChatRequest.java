package com.tokengateway.dto.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Ollama /api/chat request body.
 * Reference: https://github.com/ollama/ollama/blob/main/docs/api.md#generate-a-chat-completion
 */
@Data
@Builder
public class OllamaChatRequest {

    @JsonProperty("model")
    private String model;

    @JsonProperty("messages")
    private List<Message> messages;

    /** Must be false — we want a single complete response, not a stream. */
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
