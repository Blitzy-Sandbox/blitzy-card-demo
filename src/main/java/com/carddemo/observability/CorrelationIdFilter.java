/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a stable correlation ID to every HTTP request and publishes it to the
 * SLF4J {@link MDC} under the key {@code correlationId} so it appears on every
 * structured log line emitted while the request is handled.
 *
 * <p>A client-supplied {@code X-Correlation-Id} header is reused only when it
 * matches a strict safe-character pattern; otherwise a fresh {@link UUID} is
 * generated, which both supplies a default and prevents header-based log
 * injection. The resolved value is echoed back on the response so callers can
 * correlate across services.</p>
 *
 * <p>Distributed-tracing identifiers ({@code traceId} / {@code spanId}) are
 * owned by Micrometer Tracing and are intentionally left untouched here; only
 * the {@code correlationId} key is added and removed by this filter.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** SLF4J MDC key under which the correlation ID is published; matches {@code logback-spring.xml}. */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Inbound and outbound HTTP header carrying the correlation ID. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** Permitted shape for a client-supplied correlation ID (defends against log forging). */
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Reuses the candidate when it is non-null and matches {@link #SAFE_CORRELATION_ID};
     * otherwise returns a freshly generated UUID.
     *
     * @param candidate the client-supplied header value, may be {@code null}
     * @return a safe correlation ID to use for the request
     */
    private static String resolveCorrelationId(String candidate) {
        if (candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
