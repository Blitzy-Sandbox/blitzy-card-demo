package com.carddemo.auth.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The REAL correlation-ID hop for {@code auth-svc} (CardDemo walking skeleton).
 *
 * <p>Runs once per request (extends {@link OncePerRequestFilter}) and is registered
 * automatically as a Spring {@link Component} through the component scan rooted at
 * {@code AuthApplication}. It establishes and propagates a per-request correlation
 * identifier so a single browser action can be traced across every service boundary
 * via the SLF4J {@link MDC}. For each inbound request it:</p>
 * <ol>
 *   <li>reads the correlation-ID header, whose name comes from the
 *       {@code carddemo.correlation.header} property (default {@code X-Correlation-ID});</li>
 *   <li>generates a fresh random UUID when that header is missing or blank, making
 *       {@code auth-svc} resilient as a first hop (in the tracer the UI/BFF already supply
 *       an id, which is then propagated unchanged);</li>
 *   <li>places the value into the SLF4J {@link MDC} under the key {@code correlationId}
 *       (rendered by the {@code %X{correlationId:-}} console log pattern configured in
 *       {@code application.yml});</li>
 *   <li>echoes the value back on the response header so callers and integration tests can
 *       observe the propagation;</li>
 *   <li>removes the MDC key in a {@code finally} block for thread-pool safety.</li>
 * </ol>
 *
 * <p>Authentication in {@code auth-svc} is a permissive stub, yet this correlation hop is
 * genuine (AAP &sect;0.4 / &sect;0.8): the identifier flows UI -&gt; BFF -&gt; auth-svc and is
 * surfaced on every log line via the MDC. The {@code finally}-block cleanup is mandatory
 * because the embedded servlet container serves requests from pooled worker threads; a
 * value left in the MDC would leak into the next, unrelated request handled by the same
 * thread.</p>
 *
 * <p>Ordered at {@link Ordered#HIGHEST_PRECEDENCE} so the correlation id is established
 * before any other filter or log statement in the chain runs.</p>
 *
 * <p>Provenance: [SRC: COSGN00C | COSGN00.bms] &mdash; app/csd/CARDDEMO.CSD
 * ({@code DEFINE PROGRAM(COSGN00C) GROUP(CARDDEMO)} / {@code DEFINE TRANSACTION(CC00)}
 * over the {@code USRSEC} VSAM KSDS); the legacy CICS Sign-On transaction whose modern
 * permissive-authentication bounded context this service represents.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** MDC key printed by the {@code application.yml} log pattern {@code %X{correlationId:-}}. */
    private static final String MDC_KEY = "correlationId";

    /**
     * Name of the HTTP header that carries the correlation ID. Sourced from the
     * {@code carddemo.correlation.header} property (overridable via the
     * {@code CORRELATION_HEADER} environment variable) and defaulting to
     * {@code X-Correlation-ID}, keeping the header consistent across all services.
     */
    private final String correlationHeader;

    /**
     * Creates the filter, binding the configurable correlation-ID header name.
     *
     * @param correlationHeader the header name resolved from
     *                          {@code carddemo.correlation.header}; falls back to
     *                          {@code X-Correlation-ID} when the property is undefined
     */
    public CorrelationIdFilter(
            @Value("${carddemo.correlation.header:X-Correlation-ID}") String correlationHeader) {
        this.correlationHeader = correlationHeader;
    }

    /**
     * Establishes the correlation ID for the current request, publishes it to the SLF4J
     * {@link MDC} and the response header, then guarantees MDC cleanup so the value does
     * not bleed across pooled request threads.
     *
     * @param request     the current HTTP request; supplies the inbound correlation header
     * @param response    the current HTTP response; receives the echoed correlation header
     * @param filterChain the remainder of the servlet filter chain to invoke
     * @throws ServletException if the downstream chain raises a servlet error
     * @throws IOException      if the downstream chain raises an I/O error
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(correlationHeader);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(correlationHeader, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
