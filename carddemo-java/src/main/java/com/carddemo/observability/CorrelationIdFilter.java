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
 * Servlet filter that establishes a per-request correlation identifier and
 * publishes it to the SLF4J {@link MDC} so every log line emitted while handling
 * the request can be tied back to a single, traceable id.
 *
 * <p>The id is stored under the MDC key {@code correlationId}. The companion
 * {@code logback-spring.xml} configuration renders that exact key into the
 * structured JSON output and into the local-profile console pattern
 * ({@code %X{correlationId}}), so the key name is a binding contract and must
 * not change.</p>
 *
 * <p>Resolution strategy applied to every request:</p>
 * <ul>
 *   <li>When the inbound {@code X-Correlation-Id} header carries a value it is
 *       sanitized (trimmed, restricted to {@code [A-Za-z0-9_-]}, and truncated to
 *       {@value #MAX_LENGTH} characters) to guard against log/MDC injection.</li>
 *   <li>When the header is absent, blank, or sanitizes to an empty string, a
 *       fresh {@link UUID} is generated.</li>
 * </ul>
 *
 * <p>The resolved id is echoed back on the response through the same
 * {@code X-Correlation-Id} header before the chain executes, so it is present
 * even if the response is committed downstream, allowing clients to correlate
 * their call. The MDC entry is always removed in a {@code finally} block to
 * prevent the value from leaking onto the next request served by a reused
 * (thread-pool or virtual) thread.</p>
 *
 * <p>This filter owns the {@code correlationId} key only. The {@code traceId}
 * and {@code spanId} MDC values are populated independently by Micrometer
 * Tracing (the OpenTelemetry bridge) and are intentionally not touched here.</p>
 *
 * <p>Registered with {@link Ordered#HIGHEST_PRECEDENCE} so the correlation id is
 * present in the MDC before any other filter (including the Spring Security
 * filter chain) produces a log entry for the request.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * SLF4J MDC key under which the correlation id is stored. Must match the
     * {@code %X{correlationId}} reference in {@code logback-spring.xml} exactly.
     */
    private static final String MDC_KEY = "correlationId";

    /**
     * HTTP header used to read an inbound correlation id and to echo the
     * resolved id back to the caller.
     */
    private static final String HEADER_NAME = "X-Correlation-Id";

    /** Maximum number of characters accepted from an inbound correlation id. */
    private static final int MAX_LENGTH = 64;

    /** Matches any character that is not permitted in a correlation id. */
    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9_-]");

    /**
     * Resolves the correlation id for the current request, publishes it to the
     * MDC and the response header, invokes the remaining filter chain, and
     * guarantees the MDC entry is cleared afterwards.
     *
     * @param request     the current HTTP request
     * @param response     the current HTTP response
     * @param filterChain the remaining filter chain to delegate to
     * @throws ServletException if the downstream chain raises a servlet error
     * @throws IOException      if the downstream chain raises an I/O error
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = sanitize(request.getHeader(HEADER_NAME));
        if (correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Normalizes a candidate correlation id supplied by a caller. The value is
     * trimmed, any character outside {@code [A-Za-z0-9_-]} is removed, and the
     * remaining text is truncated to {@link #MAX_LENGTH} characters.
     *
     * @param raw the raw header value, possibly {@code null}
     * @return the sanitized id, or an empty string when nothing usable remains
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = SAFE.matcher(raw.trim()).replaceAll("");
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH);
        }
        return cleaned;
    }
}
