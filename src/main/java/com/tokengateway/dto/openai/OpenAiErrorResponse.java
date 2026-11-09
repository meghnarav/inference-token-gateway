package com.tokengateway.dto.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * OpenAI error envelope: {"error": {"message": "...", "type": "...", "code": "..."}}
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiErrorResponse {

    @JsonProperty("error")
    private ErrorDetail error;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorDetail {
        @JsonProperty("message")
        private String message;

        @JsonProperty("type")
        private String type;

        @JsonProperty("code")
        private String code;
    }

    public String message() {
        return error != null ? error.getMessage() : "Unknown OpenAI error";
    }
}
