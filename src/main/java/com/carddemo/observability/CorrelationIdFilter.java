package com.carddemo.observability;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that establishes a per-request <em>correlation ID</em> and publishes it into the
 * SLF4J {@link MDC} so that every structured log line emitted while handling a request can be tied
 * back to that single request.
 *
 * <p>This is the request-side half of the CardDemo observability trio. The legacy COBOL/CICS system
 * had no observability infrastructure at all; this filter is net-new and closes that gap for the
 * migrated Spring Boot service. See {@code docs/decision-log.md} for the rationale behind the shared
 * correlation-ID contract (rationale is documented there rather than inline, per the Explainability
 * rule).</p>
 *
 * <h2>Shared MDC-key contract</h2>
 * <p>The correlation ID is stored under the MDC key {@link #CORRELATION_ID_MDC_KEY} (literal value
 * {@code "correlationId"}). This exact key is a hard contract shared by three collaborators:</p>
 * <ul>
 *   <li>this filter, which populates it for every REST request;</li>
 *   <li>the batch job-listener counterpart, which populates the same key for batch executions; and</li>
 *   <li>{@code src/main/resources/logback-spring.xml}, whose console appender renders
 *       {@code %X{correlationId}} and whose JSON (logstash) appender includes the full MDC.</li>
 * </ul>
 * <p>If any collaborator uses a different key, end-to-end log correlation silently breaks; therefore
 * both the MDC key and the HTTP header name are exposed as reusable public constants so callers
 * reference them instead of re-typing string literals.</p>
 *
 * <h2>Ordering</h2>
 * <p>The filter runs at {@link Ordered#HIGHEST_PRECEDENCE} so the correlation ID is present in the
 * MDC before any other filter, handler, or interceptor has a chance to log.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>The MDC is backed by a thread-local map and servlet-container worker threads are pooled, so the
 * value is always removed in a {@code finally} block to prevent it from bleeding into the next
 * request served by the same thread. A targeted {@link MDC#remove(String)} is used (never
 * {@link MDC#clear()}) so that tracing-provided MDC entries such as {@code traceId} / {@code spanId}
 * contributed by Micrometer Tracing are left untouched.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * MDC key under which the correlation ID is published. Must remain exactly {@code "correlationId"}
     * to stay aligned with {@code logback-spring.xml} and the batch job-listener.
     */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * HTTP header used to both receive an upstream correlation ID (for cross-service propagation) and
     * echo the resolved value back on the response.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * Maximum number of characters retained from an inbound correlation ID. Inbound values are
     * untrusted, so their length is capped to bound MDC / log-line size. A freshly generated UUID is
     * 36 characters, comfortably below this ceiling.
     */
    private static final int MAX_CORRELATION_ID_LENGTH = 64;

    /**
     * Resolves the correlation ID for the current request, publishes it to the MDC, echoes it on the
     * response, delegates to the rest of the chain, and unconditionally removes it from the MDC when
     * the request completes.
     *
     * @param request     the inbound HTTP request (never {@code null})
     * @param response    the outbound HTTP response (never {@code null})
     * @param filterChain the remaining filter chain to delegate to (never {@code null})
     * @throws ServletException if the downstream chain raises a servlet-level failure
     * @throws IOException      if the downstream chain raises an I/O failure
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain)
            throws ServletException, IOException {

        // Reuse a validated upstream ID when present (cross-service propagation); otherwise mint one.
        final String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));

        // Publish before delegating so every downstream log statement carries the ID.
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);

        // Echo before the chain runs so the header is present even if a handler commits the response.
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Mandatory cleanup: threads are pooled, so a leaked value would corrupt the next request.
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }

    /**
     * Returns a safe correlation ID: the sanitized inbound value when it is usable, otherwise a newly
     * generated random UUID.
     *
     * @param inboundValue the raw, untrusted value of the {@link #CORRELATION_ID_HEADER} header (may
     *                     be {@code null})
     * @return a non-null, injection-safe correlation ID
     */
    private static String resolveCorrelationId(final String inboundValue) {
        final String sanitized = sanitize(inboundValue);
        return (sanitized != null) ? sanitized : UUID.randomUUID().toString();
    }

    /**
     * Sanitizes an untrusted inbound correlation ID to defend against log- and response-header
     * injection. The value is trimmed, every ISO control character (including CR {@code '\r'} and LF
     * {@code '\n'}) is stripped, and the result is capped at {@link #MAX_CORRELATION_ID_LENGTH}
     * characters.
     *
     * @param candidate the raw inbound value (may be {@code null})
     * @return a cleaned, non-blank correlation ID, or {@code null} if the input was absent, blank, or
     *         contained nothing usable after sanitization (signalling that a fresh ID should be minted)
     */
    private static String sanitize(final String candidate) {
        if (candidate == null) {
            return null;
        }
        final String trimmed = candidate.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        final int limit = Math.min(trimmed.length(), MAX_CORRELATION_ID_LENGTH);
        final StringBuilder builder = new StringBuilder(limit);
        for (int i = 0; i < limit; i++) {
            final char current = trimmed.charAt(i);
            // Drop CR, LF, and any other ISO control character to prevent header splitting / log forging.
            if (!Character.isISOControl(current)) {
                builder.append(current);
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }
}
