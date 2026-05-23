package com.tokengateway.util;

import org.springframework.stereotype.Component;

/**
 * Estimates token counts from text without a full tokenizer.
 *
 * Real-world LLMs use BPE tokenizers (e.g., cl100k_base for GPT-4).
 * This implementation uses a heuristic that closely approximates GPT-style
 * tokenization: ~1.3 tokens per word on average for English prose.
 *
 * For production: swap this with the official tokenizer library
 * (e.g., com.knuddels:jtokkit for OpenAI models).
 */
@Component
public class TokenCounter {

    private static final double TOKENS_PER_WORD = 1.3;
    // Typical overhead for system prompts, formatting, etc.
    private static final int BASE_TOKEN_OVERHEAD = 4;

    /**
     * Estimates the number of tokens in a given text.
     *
     * @param text The text to estimate tokens for
     * @return Estimated token count (always >= 1)
     */
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return BASE_TOKEN_OVERHEAD;
        }
        // Split on whitespace and punctuation boundaries
        String[] words = text.trim().split("\\s+");
        int wordCount = words.length;

        // Account for punctuation-heavy content generating extra tokens
        long punctuationCount = text.chars()
                .filter(c -> ".,;:!?()[]{}\"'".indexOf(c) >= 0)
                .count();

        int estimated = (int) Math.ceil(wordCount * TOKENS_PER_WORD + punctuationCount * 0.1) + BASE_TOKEN_OVERHEAD;
        return Math.max(1, estimated);
    }

    /**
     * Estimates total tokens for a request (prompt + expected response).
     * Used for pre-flight rate limit checks.
     *
     * @param prompt   The input prompt
     * @param responseWords Expected response word count
     */
    public int estimateRequestTokens(String prompt, int responseWords) {
        int promptTokens = estimateTokens(prompt);
        int responseTokens = (int) Math.ceil(responseWords * TOKENS_PER_WORD) + BASE_TOKEN_OVERHEAD;
        return promptTokens + responseTokens;
    }
}
