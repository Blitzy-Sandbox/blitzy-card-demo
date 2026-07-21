package com.carddemo.card.config;

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
 * The REAL correlation-ID hop for {@code card-svc} (CardDemo walking skeleton).
 *
 * <p>Runs once per request (extends {@link OncePerRequestFilter}) and is registered
 * automatically as a {@link Component} via the component scan rooted at
 * {@code CardApplication}. For every request it:</p>
 * <ol>
 *   <li>reads the correlation-ID header (name from {@code carddemo.correlation.header},
 *       default {@code X-Correlation-ID});</li>
 *   <li>trusts the inbound header ONLY when it is a bounded canonical UUID, and
 *       generates a fresh random UUID otherwise (missing, blank, malformed,
 *       over-long, or carrying injection characters) &mdash; so no unvalidated
 *       client input reaches the logs or the response header (CWE-20);</li>
 *   <li>places the validated value into the SLF4J {@link MDC} under key {@code correlationId}
 *       (printed by the {@code %X{correlationId:-}} log pattern);</li>
 *   <li>echoes the value back on the response header;</li>
 *   <li>clears the MDC key in a {@code finally} block for thread-pool safety.</li>
 * </ol>
 *
 * <p>Authentication is a permissive stub, but this correlation hop is real
 * (AAP &sect;0.4 / &sect;0.8): the identifier propagates UI -&gt; BFF -&gt; card-svc so a
 * single request can be traced across every service boundary via the SLF4J MDC.</p>
 *
 * <p>The {@code finally}-block cleanup is mandatory: Tomcat serves requests from a
 * pooled worker thread, so a value left in the MDC would bleed into the next,
 * unrelated request handled by the same thread.</p>
 *
 * <p>Provenance: [SRC: COCRDSLC | CARDDAT] &mdash; app/csd/CARDDEMO.CSD
 * ({@code DEFINE PROGRAM(COCRDSLC)} / {@code TRANSID(CCDL)} over
 * {@code DEFINE FILE(CARDDAT)}); the tracer read path this filter instruments.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** MDC key printed by the application.yml log pattern {@code %X{correlationId:-}}. */
    private static final String MDC_KEY = "correlationId";

    /**
     * Strict canonical RFC&nbsp;4122 UUID pattern (8-4-4-4-12 hex, exactly 36 chars).
     * An inbound correlation-ID header is trusted ONLY when it matches this bound; any
     * other value (malformed, over-long, or carrying CR/LF or other control characters
     * for log- or response-header injection) is rejected and replaced with a freshly
     * generated UUID. This closes CWE-20 on the value written to the SLF4J MDC and
     * echoed back on the response header.
     */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * Name of the HTTP header that carries the correlation ID. Sourced from the
     * {@code carddemo.correlation.header} property (overridable via the
     * {@code CORRELATION_HEADER} environment variable) and defaulting to
     * {@code X-Correlation-ID}, keeping the header consistent across all services.
     */
    @Value("${carddemo.correlation.header:X-Correlation-ID}")
    private String headerName;

    /**
     * Establishes the correlation ID for the current request, publishes it to the
     * SLF4J {@link MDC} and the response, then guarantees MDC cleanup.
     *
     * @param request     the current HTTP request; supplies the inbound correlation header
     * @param response     the current HTTP response; receives the echoed correlation header
     * @param filterChain the remainder of the servlet filter chain to invoke
     * @throws ServletException if the downstream chain raises a servlet error
     * @throws IOException      if the downstream chain raises an I/O error
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        // Trust the inbound correlation ID only when it is a bounded canonical UUID;
        // otherwise (missing, blank, malformed, over-long, or injection-bearing) mint a
        // fresh one. Only the validated value is written to the MDC and echoed back, so
        // no unvalidated client input can reach the logs or the response headers (CWE-20).
        String inbound = request.getHeader(headerName);
        String correlationId = (inbound != null && CANONICAL_UUID.matcher(inbound).matches())
                ? inbound
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(headerName, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
