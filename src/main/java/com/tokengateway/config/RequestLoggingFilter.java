package com.tokengateway.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that:
 * 1. Assigns a correlation ID to every request (X-Request-ID header or generated)
 * 2. Sets MDC context for structured logging throughout the request
 * 3. Logs request/response summary with timing
 * 4. Propagates correlation ID in response headers for client-side tracing
 */
@Component
@Order(1)
@Slf4j
public class RequestLoggingFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request   = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // Use client-provided request ID or generate a new one
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startMs = System.currentTimeMillis();
        try {
            log.info("[HTTP] {} {} from={}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            chain.doFilter(servletRequest, servletResponse);
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;
            log.info("[HTTP] {} {} → {} ({}ms)",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.clear();
        }
    }
}
