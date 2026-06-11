package com.cardemo.observability;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Per-request correlation-ID servlet {@link Filter} for the greenfield Java 25 LTS + Spring Boot
 * 3.5.11 migration of the AWS CardDemo COBOL/CICS mainframe application.
 *
 * <h2>Provenance &mdash; net-new observability component (no COBOL equivalent)</h2>
 * <p>This class has <strong>no COBOL source</strong>: it is a pure technology-substitution
 * component in the net-new cross-cutting observability layer. The legacy AWS CardDemo COBOL/CICS
 * estate shipped <em>zero</em> logging/observability infrastructure &mdash; no log framework, no
 * correlation identifiers, no structured output (tech-spec L818). Per the <strong>Minimal Change
 * Clause</strong> (AAP &sect;0.7.1), this file only makes the running application observable; it
 * alters <em>no</em> business behaviour. Traceability to the frozen legacy baseline is by original
 * COBOL repository commit SHA {@code 27d6c6f}; the COBOL source is <em>never copied</em> into this
 * repository (AAP &sect;0.7.2 &mdash; Preservation Requirements).</p>
 *
 * <h2>Single responsibility</h2>
 * <p>At the very start of every HTTP request this filter establishes a <strong>correlation
 * ID</strong> and binds it to the SLF4J <strong>MDC</strong> (Mapped Diagnostic Context) for the
 * duration of the request, then removes it when the request completes. This satisfies the
 * requirement that &quot;every HTTP request generates a correlation ID propagated&hellip;via MDC&quot;
 * (tech-spec L820, &sect;0.7.1 Observability Implementation Analysis, L816-L836). The file role is
 * defined by the authoritative target tree as &quot;Request correlation ID injection&quot;
 * (tech-spec L459).</p>
 *
 * <h2>Cross-file MDC key contract (character-exact)</h2>
 * <p>The correlation ID is published under the MDC key {@value #CORRELATION_ID_MDC_KEY}. This
 * literal is a <strong>character-exact cross-file contract</strong> with the sibling
 * {@code src/main/resources/logback-spring.xml}, whose structured-JSON appender
 * ({@code net.logstash.logback.encoder.LogstashEncoder} with {@code includeMdc=true}) renders every
 * MDC entry as a top-level JSON field, and whose human-readable console pattern emits
 * {@code %X{correlationId}} inline alongside {@code traceId}/{@code spanId}. Any deviation from the
 * exact key {@code "correlationId"} silently breaks structured logging.</p>
 *
 * <h2>{@code X-Correlation-Id} header convention</h2>
 * <p>The HTTP header {@value #CORRELATION_ID_HEADER} is used to both <em>honour</em> and
 * <em>advertise</em> the correlation ID:</p>
 * <ul>
 *   <li><strong>Honour-if-present, else generate.</strong> If an upstream caller (gateway, client,
 *       or a calling service) supplies a non-blank {@code X-Correlation-Id} header, that exact value
 *       is adopted so a single ID spans the whole call chain. Otherwise a fresh
 *       {@link UUID#randomUUID() random UUID} is generated.</li>
 *   <li><strong>Echo-back.</strong> The chosen value is written to the response
 *       {@code X-Correlation-Id} header (before the filter chain proceeds, so it is committed before
 *       the body is flushed) and is also surfaced in the JSON error envelope by the controller-advice
 *       layer, letting clients and log aggregators correlate a response with its server-side log
 *       lines.</li>
 * </ul>
 *
 * <h2>Registration &amp; ordering</h2>
 * <p>The filter is a <strong>self-registering {@code @Component}</strong>: Spring Boot auto-detects
 * it (base package {@code com.cardemo}, decision <strong>D-006</strong>, matching
 * {@code <groupId>com.cardemo</groupId>}) and inserts it into the servlet filter chain. It is
 * deliberately <em>not</em> registered through a {@code FilterRegistrationBean} in any
 * {@code @Configuration}; the sibling {@code config/ObservabilityConfig.java} owns only the
 * Micrometer {@code ObservedAspect} bean and explicitly does not register this filter (this resolves
 * the loose mention at tech-spec L822 in favour of the standalone filter named at L459).</p>
 * <p>{@link #getOrder()} returns {@link Ordered#HIGHEST_PRECEDENCE} so the correlation ID is present
 * in the MDC <em>before</em> any downstream filter, controller, or service emits a log line. A small
 * positive offset (for example {@code HIGHEST_PRECEDENCE + 10}) would also be acceptable to sit just
 * after Spring's own request-context filters; highest precedence is chosen here to maximise the span
 * of log lines that carry a correlation ID, and the {@code correlationId} key is independent of the
 * {@code traceId}/{@code spanId} keys, so relative ordering against the tracing filter does not
 * affect downstream log enrichment.</p>
 *
 * <h2>Separation of concerns (boundaries this filter does not cross)</h2>
 * <ul>
 *   <li>It does <strong>not</strong> manage {@code traceId}/{@code spanId} &mdash; those are supplied
 *       automatically by the Micrometer OpenTelemetry tracing bridge
 *       ({@code micrometer-tracing-bridge-otel}). This filter manages only the custom
 *       {@code correlationId}.</li>
 *   <li>It does <strong>not</strong> define logging appenders or JSON encoders &mdash; that is owned
 *       by {@code logback-spring.xml}.</li>
 *   <li>It performs <strong>no</strong> database, S3, SQS, metrics, health-check, or security work.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>The filter holds no mutable state and is therefore safe to share across all request threads.
 * The MDC entry is always removed in a {@code finally} block: Tomcat reuses worker threads from a
 * pool, so a leaked MDC entry would mislabel a subsequent, unrelated request. {@link MDC#remove(String)}
 * (rather than {@link MDC#clear()}) is used so the {@code traceId}/{@code spanId} entries that the
 * tracing bridge may manage on the same thread are not wiped.</p>
 *
 * @see Ordered
 * @see org.slf4j.MDC
 */
@Component
public class CorrelationIdFilter implements Filter, Ordered {

    /**
     * MDC key under which the correlation ID is published for the lifetime of the request.
     *
     * <p>This value MUST remain exactly {@code "correlationId"}: it is matched verbatim by the
     * {@code %X{correlationId}} token and the {@code includeMdc} JSON rendering in
     * {@code logback-spring.xml}. Changing it breaks structured logging.</p>
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Inbound/outbound HTTP header carrying the correlation ID.
     *
     * <p>Honoured when supplied by an upstream caller (so one ID spans the call chain) and always
     * echoed back on the response so clients and log aggregators can correlate.</p>
     */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * Establishes the correlation ID in the MDC for the request, echoes it on the response, invokes
     * the remainder of the filter chain, and unconditionally removes the MDC entry afterwards.
     *
     * <p>For a non-HTTP {@link ServletRequest}/{@link ServletResponse} (not expected in this Spring
     * MVC application, but handled defensively) the chain is simply continued without touching the
     * MDC.</p>
     *
     * @param request the servlet request
     * @param response the servlet response
     * @param chain the remainder of the filter chain
     * @throws IOException if the downstream chain raises an I/O error
     * @throws ServletException if the downstream chain raises a servlet error
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        // Java 25 instanceof pattern matching: only HTTP exchanges carry headers / participate in
        // correlation. A non-HTTP request cannot supply or echo the header, so it passes straight
        // through with no MDC work.
        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            // Honour an upstream-supplied correlation ID; otherwise mint a fresh one so EVERY
            // request is correlatable (tech-spec L820).
            String correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }

            // Bind to the SLF4J MDC: from here until the finally block, every log line on this
            // thread carries the "correlationId" field consumed by logback-spring.xml.
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            // Echo BEFORE proceeding so the header is committed before the response is flushed.
            httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);

            try {
                chain.doFilter(request, response);
            } finally {
                // MANDATORY for thread-pool reuse safety. Remove ONLY our key so traceId/spanId
                // managed by the tracing bridge on this thread are preserved.
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * Runs this filter as early as possible so the correlation ID is in the MDC before any
     * downstream component logs.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
