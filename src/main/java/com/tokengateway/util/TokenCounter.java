package com.tokengateway.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Counts tokens using JTokkit — the official Java port of the tiktoken library.
 *
 * Uses cl100k_base encoding (GPT-3.5/GPT-4 / text-embedding-ada-002) by default,
 * which is also a close approximation for Llama and other open-weight models.
 *
 * This replaces the word-count heuristic. Exact tokenization matters for:
 *  - Accurate pre-flight rate limit checks
 *  - Correct token refund calculations
 *  - Honest usage reporting to users
 *
 * JTokkit is thread-safe and stateless — safe to use as a singleton bean.
 */
@Component
@Slf4j
public class TokenCounter {

    // Fallback heuristic constants (used only if JTokkit is unavailable)
    private static final double TOKENS_PER_WORD   = 1.3;
    private static final int    BASE_TOKEN_OVERHEAD = 4;

    private final Encoding encoding;

    public TokenCounter() {
        Encoding enc;
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            enc = registry.getEncoding(EncodingType.CL100K_BASE);
            log.info("[TOKEN-COUNTER] Initialized JTokkit with cl100k_base encoding");
        } catch (Exception e) {
            log.warn("[TOKEN-COUNTER] JTokkit initialization failed, falling back to heuristic: {}", e.getMessage());
            enc = null;
        }
        this.encoding = enc;
    }

    /**
     * Returns the exact BPE token count for the given text.
     * Falls back to a word-count heuristic if JTokkit is unavailable.
     *
     * @param text Input text (prompt or response)
     * @return Token count (always >= 1)
     */
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return BASE_TOKEN_OVERHEAD;
        }
        if (encoding != null) {
            try {
                int count = encoding.countTokens(text);
                return Math.max(1, count + BASE_TOKEN_OVERHEAD);
            } catch (Exception e) {
                log.debug("[TOKEN-COUNTER] JTokkit count failed, using heuristic: {}", e.getMessage());
            }
        }
        return heuristicCount(text);
    }

    /**
     * Estimates total tokens for a full request (prompt + expected response words).
     * Used for pre-flight rate limit checks before the actual LLM call.
     */
    public int estimateRequestTokens(String prompt, int responseWords) {
        int promptTokens   = estimateTokens(prompt);
        int responseTokens = (int) Math.ceil(responseWords * TOKENS_PER_WORD) + BASE_TOKEN_OVERHEAD;
        return promptTokens + responseTokens;
    }

    private int heuristicCount(String text) {
        String[] words = text.trim().split("\\s+");
        long punctuation = text.chars()
                .filter(c -> ".,;:!?()[]{}\"'".indexOf(c) >= 0)
                .count();
        int estimated = (int) Math.ceil(words.length * TOKENS_PER_WORD + punctuation * 0.1) + BASE_TOKEN_OVERHEAD;
        return Math.max(1, estimated);
    }
}
