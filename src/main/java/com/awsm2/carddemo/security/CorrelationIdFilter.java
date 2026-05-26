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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.awsm2.carddemo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter that establishes a per-request correlation identifier for
 * end-to-end distributed tracing across CloudWatch Logs, OpenSearch indexes,
 * CloudTrail events, and downstream Kafka events.
 *
 * <p><b>Replaces:</b> implicit CICS task-context propagation. In the source
 * z/OS CardDemo system, every CICS task carried its own TWA (Transaction Work
 * Area) and EIB (Exec Interface Block) which uniquely identified the task on
 * the mainframe. The Java target uses a per-request correlation identifier
 * propagated via the SLF4J {@link MDC} (Mapped Diagnostic Context) so that
 * every log line emitted during request processing carries the same token,
 * and so that downstream Kafka publishes (via
 * {@link com.awsm2.carddemo.adapter.MdcHeaderProducerInterceptor}) and audit
 * emissions (via {@link com.awsm2.carddemo.adapter.AuditLogService}) inherit
 * the same identifier.</p>
 *
 * <h2>QA CP11 Finding M-1 fix</h2>
 * <p>The QA Final-CP11 review noted a contract gap: {@code ApiResponse.java}
 * L119-L124 documents that {@code correlationId} "echoes the
 * {@code X-Correlation-Id} request header if present; otherwise generated
 * server-side", but the implementation in
 * {@code GlobalExceptionHandler.generateCorrelationId()} always returned a
 * fresh {@link UUID}. This filter closes that gap by:</p>
 * <ol>
 *   <li>Reading the inbound {@code X-Correlation-Id} HTTP request header.</li>
 *   <li>If present and well-formed (see {@link #ALLOWED_PATTERN}), copying
 *       the value into the {@link MDC} under key {@code correlationId}.</li>
 *   <li>If absent or malformed, generating a fresh {@link UUID#randomUUID()}
 *       string and placing it in the MDC instead.</li>
 *   <li>Echoing the resolved value back to the client via the
 *       {@code X-Correlation-Id} response header before invoking the
 *       filter chain.</li>
 *   <li>On filter exit (success or exception), removing the MDC entry to
 *       prevent leakage across servlet-thread reuse.</li>
 * </ol>
 *
 * <p>{@code GlobalExceptionHandler.generateCorrelationId()} then prefers the
 * MDC value over a fresh UUID so that error responses surface the same
 * correlation token visible in CloudWatch log lines and downstream Kafka
 * headers (see {@link com.awsm2.carddemo.adapter.MdcHeaderProducerInterceptor}
 * which already reads {@code MDC.get("correlationId")}).</p>
 *
 * <h2>Security and PCI-DSS hygiene (AAP &sect;0.6.6, &sect;0.7.2)</h2>
 * <p>The inbound header is sanitised against {@link #ALLOWED_PATTERN} to
 * defend against:</p>
 * <ul>
 *   <li><b>Log injection</b> &mdash; CRLF / TAB characters in the header
 *       value could be used by an attacker to inject fake log lines into
 *       structured log streams. The allow-list pattern admits only the
 *       characters used by RFC 4122 UUIDs (hex + hyphen) plus underscore
 *       and dot, which are sufficient for most distributed-tracing systems
 *       (Zipkin, AWS X-Ray trace IDs, OpenTelemetry trace IDs).</li>
 *   <li><b>Header bombing</b> &mdash; the value is bounded by
 *       {@link #MAX_HEADER_LENGTH} so a malicious caller cannot exhaust
 *       memory by sending a multi-megabyte header.</li>
 *   <li><b>MDC leakage</b> &mdash; the {@code try/finally} block in
 *       {@link #doFilterInternal} guarantees the MDC entry is removed even
 *       on exception, preventing reuse of the stale identifier on the next
 *       request handled by the same servlet thread.</li>
 * </ul>
 * <p>If the inbound header fails validation, the filter logs at DEBUG
 * (never WARN, to avoid generating noise from misbehaving clients) and
 * falls back to a server-generated UUID; the malformed inbound value is
 * NEVER copied into MDC, response headers, or downstream consumers.</p>
 *
 * <h2>Filter ordering</h2>
 * <p>This filter is registered with order {@link Ordered#HIGHEST_PRECEDENCE} +
 * 10 so it runs <em>before</em> the {@link JwtAuthenticationFilter} (which
 * has no explicit order). The correlation identifier MUST be in the MDC
 * before any authentication or business logic runs, so that authentication
 * failures, JWT validation errors, and access-denied responses all carry the
 * same identifier.</p>
 *
 * <h2>MDC key contract</h2>
 * <p>The MDC key {@code correlationId} matches the key already consumed by:</p>
 * <ul>
 *   <li>{@link com.awsm2.carddemo.adapter.MdcHeaderProducerInterceptor}
 *       (Kafka producer side) &mdash; propagates the value to consumers via
 *       Kafka record headers.</li>
 *   <li>The Logback {@code <pattern>} converter (see
 *       {@code logback-spring.xml}) &mdash; emits the correlation ID on
 *       every console log line and within structured JSON.</li>
 *   <li>{@link com.awsm2.carddemo.exception.GlobalExceptionHandler#generateCorrelationId()}
 *       &mdash; surfaces the value on error response envelopes.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.adapter.MdcHeaderProducerInterceptor
 * @see com.awsm2.carddemo.exception.GlobalExceptionHandler
 * @see com.awsm2.carddemo.dto.ApiResponse
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * HTTP header name for inbound correlation identifiers (case-insensitive
     * per RFC 7230 &sect;3.2). The same name is echoed back to the client
     * via the response header so clients can see the resolved identifier
     * without parsing the response body.
     *
     * <p>This constant value matches the JavaDoc in
     * {@link com.awsm2.carddemo.dto.ApiResponse#correlationId} ("X-Correlation-Id
     * request header if present").</p>
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * MDC key under which the resolved correlation identifier is stored
     * for the duration of the request. Matches the key consumed by
     * {@link com.awsm2.carddemo.adapter.MdcHeaderProducerInterceptor}
     * ({@code MDC_KEY_CORRELATION_ID}) and the
     * {@code logback-spring.xml} pattern converters so that every log
     * line for the request carries the same identifier without explicit
     * parameter passing.
     */
    public static final String MDC_CORRELATION_ID_KEY = "correlationId";

    /**
     * Maximum permitted length of an inbound {@code X-Correlation-Id}
     * header value. Standard distributed-tracing identifiers fit comfortably
     * within 128 characters (a UUID is 36 characters; an OpenTelemetry
     * trace+span is 49 characters; an AWS X-Ray trace ID is 35 characters).
     * Values exceeding this length are rejected as malformed and replaced
     * with a server-generated UUID to defend against header-bombing
     * attacks.
     */
    private static final int MAX_HEADER_LENGTH = 128;

    /**
     * Allow-list pattern for inbound {@code X-Correlation-Id} header
     * values. The pattern admits ASCII alphanumeric characters, hyphens,
     * underscores, and dots &mdash; sufficient to express RFC 4122 UUIDs,
     * AWS X-Ray trace IDs, OpenTelemetry trace IDs, and Zipkin trace IDs.
     *
     * <p>The pattern explicitly excludes:</p>
     * <ul>
     *   <li>CRLF / TAB / control characters &mdash; defends against
     *       log-injection (see AAP &sect;0.7.2 PCI-DSS guidance).</li>
     *   <li>Spaces &mdash; would render the value indistinguishable
     *       from multi-token log lines.</li>
     *   <li>Quote / angle-bracket / ampersand characters &mdash; defend
     *       against header- and HTML-injection across downstream
     *       consumers.</li>
     * </ul>
     *
     * <p>Pre-compiled as a {@code static final} for thread-safe reuse
     * across all requests.</p>
     */
    private static final Pattern ALLOWED_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Main filter entry point invoked exactly once per HTTP request by
     * {@link OncePerRequestFilter}. Resolves the correlation identifier
     * (inbound header or server-generated UUID), publishes it to MDC,
     * echoes it to the response header, and removes it from MDC on exit.
     *
     * <p>The MDC removal in the {@code finally} block is critical: servlet
     * containers reuse worker threads across requests, so leaving the
     * value in place would associate the previous request's identifier
     * with the next request handled by the same thread. The
     * {@code try/finally} guarantees cleanup even on exception.</p>
     *
     * @param request     the inbound HTTP request; must not be {@code null}
     * @param response    the outbound HTTP response; must not be
     *                    {@code null}
     * @param filterChain the remaining filter chain; must not be
     *                    {@code null}
     * @throws ServletException if any downstream filter or the dispatched
     *                          servlet throws
     * @throws IOException      if any downstream filter or the dispatched
     *                          servlet performs an I/O operation that
     *                          fails
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        // Resolve the correlation identifier — inbound header (if valid)
        // or a freshly generated UUID — and publish it to MDC for the
        // duration of the request so every log line emitted by downstream
        // filters, controllers, services, and adapters inherits it.
        final String correlationId = resolveCorrelationId(request);
        try {
            // Publish to MDC FIRST so that any downstream emission (including
            // the response-header write below) sees the value already in
            // place.
            MDC.put(MDC_CORRELATION_ID_KEY, correlationId);

            // Echo the resolved identifier back to the client via the
            // response header. This lets the client confirm that the
            // server accepted the inbound header (or surface the
            // server-generated value when none was supplied).
            //
            // Setting the header BEFORE filterChain.doFilter() means the
            // header is present on the response even if downstream
            // filters short-circuit (e.g., on authentication failure).
            response.setHeader(CORRELATION_ID_HEADER, correlationId);

            // Proceed with the rest of the filter chain.
            filterChain.doFilter(request, response);
        } finally {
            // Always clear the MDC entry on exit — servlet containers
            // reuse threads across requests, so failing to remove the
            // value would leak it into the next request handled by the
            // same thread.
            MDC.remove(MDC_CORRELATION_ID_KEY);
        }
    }

    /**
     * Resolves the correlation identifier for the request.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Read the {@code X-Correlation-Id} request header.</li>
     *   <li>If the header is present, well-formed, and within the maximum
     *       length, trim leading/trailing whitespace and return it
     *       verbatim.</li>
     *   <li>Otherwise generate a fresh {@link UUID#randomUUID()} string
     *       and return it.</li>
     * </ol>
     *
     * <p>Validation rejects any value that does not match
     * {@link #ALLOWED_PATTERN} (alphanumeric + hyphen + underscore + dot
     * only). Rejected values are NEVER copied into MDC, response
     * headers, or downstream propagation contexts.</p>
     *
     * @param request the inbound HTTP request from which to read the
     *                {@code X-Correlation-Id} header; must not be
     *                {@code null}
     * @return the resolved correlation identifier; never {@code null},
     *         never blank
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        final String inboundHeader = request.getHeader(CORRELATION_ID_HEADER);
        if (StringUtils.hasText(inboundHeader)) {
            final String trimmed = inboundHeader.trim();
            if (isWellFormed(trimmed)) {
                // Inbound header is present and well-formed — use it
                // verbatim so distributed tracing systems can stitch the
                // request across services.
                return trimmed;
            }
            // Inbound header is present but malformed — log at DEBUG (a
            // misbehaving client should not generate WARN-level noise)
            // and fall through to UUID generation. The malformed value
            // is NEVER logged to defend against log injection.
            if (logger.isDebugEnabled()) {
                logger.debug("Inbound " + CORRELATION_ID_HEADER + " header rejected (malformed or too long); "
                        + "falling back to server-generated UUID. Method=" + request.getMethod()
                        + ", URI=" + request.getRequestURI());
            }
        }
        // No inbound header or inbound header was rejected — generate a
        // fresh UUID. The version-4 UUID is cryptographically random and
        // collisions are astronomically improbable.
        return UUID.randomUUID().toString();
    }

    /**
     * Checks whether the supplied correlation-identifier value is
     * well-formed for use in MDC and response headers.
     *
     * <p>A value is well-formed if and only if:</p>
     * <ul>
     *   <li>Its length is &le; {@link #MAX_HEADER_LENGTH}</li>
     *   <li>Every character matches {@link #ALLOWED_PATTERN}
     *       (alphanumeric + hyphen + underscore + dot)</li>
     * </ul>
     *
     * @param value the candidate identifier (already trimmed of leading
     *              and trailing whitespace); must not be {@code null}
     * @return {@code true} if the value is safe to use as a correlation
     *         identifier; {@code false} otherwise
     */
    private boolean isWellFormed(String value) {
        if (value.length() > MAX_HEADER_LENGTH) {
            return false;
        }
        return ALLOWED_PATTERN.matcher(value).matches();
    }
}
