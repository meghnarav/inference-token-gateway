package com.tokengateway.util;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Generates request-scoped correlation IDs for distributed tracing.
 * Format: req-{8-char-uuid-prefix} — short enough for logs, unique enough for correlation.
 */
@Component
public class RequestIdGenerator {

    public String generate() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
