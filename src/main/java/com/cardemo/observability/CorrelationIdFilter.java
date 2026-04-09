/*
 * CorrelationIdFilter.java
 *
 * HTTP servlet filter that generates a unique correlation ID for every incoming
 * HTTP request, injects it into the SLF4J Mapped Diagnostic Context (MDC), and
 * propagates it in the X-Correlation-ID response header. This enables end-to-end
 * request tracing across all service calls, batch job invocations, and log entries.
 *
 * COBOL Origin: The original CardDemo COBOL/CICS application had zero observability
 * infrastructure. Each CICS RETURN TRANSID started fresh with no request correlation.
 * This filter is entirely greenfield per AAP §0.7.1 and §0.8.6 ("Observability
 * ships from day one").
 *
 * MDC Integration:
 *   - correlationId : Application-level request correlation (set by this filter)
 *   - traceId       : Distributed trace identifier (set by Micrometer/OpenTelemetry)
 *   - spanId        : Current span identifier (set by Micrometer/OpenTelemetry)
 *
 * The MDC key "correlationId" MUST match the logback-spring.xml configuration:
 *   - Console pattern: %X{correlationId}
 *   - LogstashEncoder: <includeMdcKeyName>correlationId</includeMdcKeyName>
 *
 * Thread Safety:
 *   MDC is thread-local by default. The finally block ensures cleanup on every
 *   request to prevent correlation ID leaking across thread pool reuse in embedded
 *   Tomcat. Spring Boot 3.5.x handles MDC propagation for virtual threads when
 *   spring.threads.virtual.enabled=true.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.cardemo.observability;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that manages correlation ID lifecycle for HTTP requests.
 *
 * <p>Execution order: This filter is annotated with {@code @Order(Ordered.HIGHEST_PRECEDENCE)}
 * to guarantee it executes before all other filters — including Spring Security filters
 * and Micrometer tracing filters — so that the correlation ID is present in MDC from the
 * very start of request processing.</p>
 *
 * <p>Correlation ID resolution strategy:</p>
 * <ol>
 *   <li>If the incoming request carries an {@code X-Correlation-ID} header (e.g., from an
 *       upstream API gateway or client), that value is preserved for distributed tracing.</li>
 *   <li>If no header is present or the header is blank, a new UUID v4 is generated.</li>
 * </ol>
 *
 * <p>The resolved correlation ID is:</p>
 * <ul>
 *   <li>Injected into SLF4J MDC under key {@value #CORRELATION_ID_MDC_KEY} — available to
 *       all downstream log statements for structured JSON and console output.</li>
 *   <li>Set as the {@code X-Correlation-ID} response header — available to clients for
 *       debugging and support ticket correlation.</li>
 * </ul>
 *
 * <p>The correlation ID is distinct from the OpenTelemetry {@code traceId}:
 * the correlation ID is application-level and can survive across multiple traces in
 * long-running workflows, whereas the trace ID is infrastructure-level and scoped
 * to a single distributed trace.</p>
 *
 * @see org.slf4j.MDC
 * @see jakarta.servlet.Filter
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    /**
     * HTTP header name used to propagate the correlation ID between client and server.
     * Incoming requests may carry this header from upstream gateways; the response always
     * includes this header with the resolved correlation ID value.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /**
     * MDC key under which the correlation ID is stored. This value MUST match the
     * logback-spring.xml configuration:
     * <ul>
     *   <li>Console pattern: {@code %X{correlationId}}</li>
     *   <li>LogstashEncoder: {@code <includeMdcKeyName>correlationId</includeMdcKeyName>}</li>
     * </ul>
     */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    /**
     * Processes an HTTP request by resolving the correlation ID, injecting it into MDC,
     * setting the response header, and delegating to the next filter in the chain.
     *
     * <p>The MDC entry is always cleaned up in a {@code finally} block to prevent
     * correlation ID leakage when threads are reused from the embedded Tomcat thread pool.</p>
     *
     * @param request  the incoming servlet request (cast to {@link HttpServletRequest})
     * @param response the outgoing servlet response (cast to {@link HttpServletResponse})
     * @param chain    the filter chain for request delegation
     * @throws IOException      if an I/O error occurs during filter chain processing
     * @throws ServletException if a servlet error occurs during filter chain processing
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Resolve correlation ID: preserve upstream header or generate new UUID
        String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Inject into SLF4J MDC — all downstream log statements will include this value
        // in structured JSON output (LogstashEncoder) and console output (%X{correlationId})
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);

        // Set response header so clients can correlate requests with server-side logs
        httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            log.debug("Request {} {} assigned correlationId={}",
                    httpRequest.getMethod(), httpRequest.getRequestURI(), correlationId);

            chain.doFilter(request, response);
        } finally {
            // CRITICAL: Remove MDC entry to prevent correlation ID leaking to other
            // requests on the same thread due to thread pool reuse in embedded Tomcat.
            // This is essential for both traditional threads and virtual threads.
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Initializes the filter. This is a no-op because Spring manages the full
     * component lifecycle via {@code @Component} registration.
     *
     * @param filterConfig the filter configuration provided by the servlet container
     * @throws ServletException never thrown by this implementation
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // No-op: Spring manages the lifecycle of this filter via @Component
        log.debug("CorrelationIdFilter initialized");
    }

    /**
     * Destroys the filter. This is a no-op because Spring manages the full
     * component lifecycle via {@code @Component} registration.
     */
    @Override
    public void destroy() {
        // No-op: Spring manages the lifecycle of this filter via @Component
        log.debug("CorrelationIdFilter destroyed");
    }
}
