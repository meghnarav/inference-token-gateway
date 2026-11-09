package com.tokengateway.config;

import com.tokengateway.service.LlmBackend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the active LlmBackend implementation at startup based on
 * the {@code gateway.llm.provider} configuration property.
 *
 * Supported values:
 *   "openai"    → OpenAiLlmBackend   (requires OPENAI_API_KEY)
 *   "ollama"    → OllamaLlmBackend   (requires local Ollama running)
 *   "simulated" → SimulatedLlmBackend (default, no external deps)
 *
 * This pattern avoids Spring conditional annotations and keeps the
 * selection logic in one readable place. All three backends are always
 * instantiated — only one is exposed as @Primary for injection into GatewayService.
 */
@Configuration
@Slf4j
public class LlmBackendRouter {

    private final GatewayProperties properties;
    private final LlmBackend openAiBackend;
    private final LlmBackend ollamaBackend;
    private final LlmBackend simulatedBackend;

    public LlmBackendRouter(GatewayProperties properties,
                            @Qualifier("openAiLlmBackend")    LlmBackend openAiBackend,
                            @Qualifier("ollamaLlmBackend")    LlmBackend ollamaBackend,
                            @Qualifier("simulatedLlmBackend") LlmBackend simulatedBackend) {
        this.properties       = properties;
        this.openAiBackend    = openAiBackend;
        this.ollamaBackend    = ollamaBackend;
        this.simulatedBackend = simulatedBackend;
    }

    /**
     * Exposes the configured backend as the primary LlmBackend bean.
     * GatewayService injects this without needing to know which provider is active.
     */
    @Bean
    @Primary
    public LlmBackend activeLlmBackend() {
        String provider = properties.getLlm().getProvider();
        LlmBackend selected = switch (provider.toLowerCase()) {
            case "openai"    -> openAiBackend;
            case "ollama"    -> ollamaBackend;
            case "simulated" -> simulatedBackend;
            default -> {
                log.warn("[LLM-ROUTER] Unknown provider '{}', falling back to simulated", provider);
                yield simulatedBackend;
            }
        };
        log.info("[LLM-ROUTER] Active LLM backend: {} (provider={})", selected.getClass().getSimpleName(), provider);
        return selected;
    }
}
