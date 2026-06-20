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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that establishes a per-request correlation identifier and exposes it to the
 * logging subsystem and to HTTP clients.
 *
 * <p>The correlation ID is stored in the SLF4J {@link MDC} under the key {@code correlationId}.
 * This key is contractually bound to {@code logback-spring.xml}, whose structured-JSON encoder and
 * {@code local}-profile console pattern render {@code %X{correlationId}}; changing the key would
 * break the logging output contract.</p>
 *
 * <p>Resolution order for each request:</p>
 * <ol>
 *   <li>If the inbound {@code X-Correlation-Id} header carries text, it is sanitized (restricted to
 *       {@code [A-Za-z0-9_-]} and truncated to {@value #MAX_LENGTH} characters) to guard against log
 *       and MDC injection.</li>
 *   <li>If the header is absent, blank, or sanitizes to an empty value, a fresh random
 *       {@link UUID} is generated.</li>
 * </ol>
 *
 * <p>The resolved value is echoed back on the {@code X-Correlation-Id} response header before the
 * downstream chain executes, allowing clients to correlate their request with server-side logs.</p>
 *
 * <p>This filter manages the {@code correlationId} key only. The {@code traceId} and {@code spanId}
 * MDC keys are populated by Micrometer Tracing (OpenTelemetry bridge) and are intentionally left
 * untouched here.</p>
 *
 * <p>The MDC entry is always removed in a {@code finally} block so the value cannot leak onto a
 * subsequent request served by a reused (thread-pool or servlet-container) thread; this holds for
 * platform and virtual threads alike, since the MDC is thread-scoped.</p>
 *
 * <p>Registered as the highest-precedence filter so the correlation ID is present in the MDC before
 * any other filter (including the Spring Security filter chain) emits a log line.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** SLF4J MDC key for the correlation identifier; must match {@code logback-spring.xml}. */
    private static final String MDC_KEY = "correlationId";

    /** Inbound and outbound HTTP header that conveys the correlation identifier. */
    private static final String HEADER_NAME = "X-Correlation-Id";

    /** Maximum number of characters accepted from an inbound correlation identifier. */
    private static final int MAX_LENGTH = 64;

    /** Matches every character that is not permitted within a correlation identifier. */
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9_-]");

    /**
     * Resolves the correlation ID for the current request, places it in the MDC, echoes it on the
     * response header, invokes the remainder of the filter chain, and removes the MDC entry once the
     * request has been fully processed.
     *
     * @param request     the current HTTP request
     * @param response    the current HTTP response
     * @param filterChain the remaining filter chain to execute
     * @throws ServletException if the downstream chain raises a servlet error
     * @throws IOException      if the downstream chain raises an I/O error
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        final String correlationId = resolveCorrelationId(request.getHeader(HEADER_NAME));
        MDC.put(MDC_KEY, correlationId);
        // Set the header before invoking the chain so it is present even if a downstream component
        // commits the response.
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Mandatory cleanup: prevents the identifier from leaking onto the next request handled
            // by a reused thread.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Resolves the effective correlation identifier from the inbound header value, generating a
     * fresh {@link UUID} when no usable value is supplied.
     *
     * @param headerValue the raw inbound {@code X-Correlation-Id} header value, possibly {@code null}
     * @return a non-empty, sanitized correlation identifier
     */
    private static String resolveCorrelationId(String headerValue) {
        if (StringUtils.hasText(headerValue)) {
            final String sanitized = sanitize(headerValue);
            if (!sanitized.isEmpty()) {
                return sanitized;
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Sanitizes an inbound correlation identifier by trimming surrounding whitespace, removing every
     * character outside {@code [A-Za-z0-9_-]}, and truncating the result to {@value #MAX_LENGTH}
     * characters. This neutralizes log- and MDC-injection vectors such as line breaks and control
     * characters.
     *
     * @param raw the raw header value (guaranteed to contain text by the caller)
     * @return the sanitized value, which may be empty if no permitted characters remain
     */
    private static String sanitize(String raw) {
        final String stripped = SAFE.matcher(raw.trim()).replaceAll("");
        if (stripped.length() > MAX_LENGTH) {
            return stripped.substring(0, MAX_LENGTH);
        }
        return stripped;
    }
}
