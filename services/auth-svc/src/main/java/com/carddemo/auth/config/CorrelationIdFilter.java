package com.carddemo.auth.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

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
 *   <li>trusts the inbound header ONLY when it is a bounded canonical UUID, and generates a
 *       fresh random UUID otherwise (missing, blank, malformed, over-long, or injection-bearing)
 *       &mdash; so no unvalidated client input reaches the logs or the response header (CWE-20);
 *       in the tracer the UI/BFF already supply a canonical id, which is propagated unchanged;</li>
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
     * Strict canonical RFC&nbsp;4122 UUID pattern (8-4-4-4-12 hex, exactly 36 chars). An inbound
     * correlation-ID header is trusted ONLY when it matches this bound; any other value (malformed,
     * over-long, or carrying CR/LF or other control characters for log- or response-header
     * injection) is rejected and replaced with a freshly generated UUID. This closes CWE-20 on the
     * value written to the SLF4J MDC and echoed back on the response header, matching card-svc's
     * bounded correlation-ID policy uniformly across every service.
     */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
        // Trust the inbound correlation ID only when it is a bounded canonical UUID; otherwise
        // (missing, blank, malformed, over-long, or injection-bearing) mint a fresh one. Only the
        // validated value reaches the SLF4J MDC and the echoed response header, so no unvalidated
        // client input can be written to the logs or the response headers (CWE-20).
        String inbound = request.getHeader(correlationHeader);
        String correlationId = (inbound != null && CANONICAL_UUID.matcher(inbound).matches())
                ? inbound
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(correlationHeader, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
