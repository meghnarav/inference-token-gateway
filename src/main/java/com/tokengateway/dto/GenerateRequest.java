package com.tokengateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRequest {

    @NotBlank(message = "userId must not be blank")
    @Size(min = 1, max = 128, message = "userId must be between 1 and 128 characters")
    @JsonProperty("userId")
    private String userId;

    @NotBlank(message = "prompt must not be blank")
    @Size(min = 1, max = 8000, message = "prompt must be between 1 and 8000 characters")
    @JsonProperty("prompt")
    private String prompt;

    /**
     * Optional idempotency key. If provided and a matching completed request exists,
     * the cached response is returned without consuming tokens.
     */
    @Size(max = 256)
    @JsonProperty("idempotencyKey")
    private String idempotencyKey;

    /**
     * Optional model hint (reserved for future use).
     */
    @JsonProperty("model")
    private String model;
}
