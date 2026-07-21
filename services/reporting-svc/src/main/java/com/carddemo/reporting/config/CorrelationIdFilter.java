package com.carddemo.reporting.config;

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
 * The REAL correlation-ID hop for reporting-svc (CardDemo walking skeleton).
 *
 * <p>Runs once per request (extends {@link OncePerRequestFilter}) and is registered
 * automatically as a {@link Component} via the component scan rooted at
 * {@code ReportingApplication}. For every request it:</p>
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
 * <p>Auth is a permissive stub, but this hop is real (AAP 0.4 / 0.8): the id propagates
 * UI -&gt; BFF -&gt; domain services. reporting-svc is a health-exempt async job stub, yet
 * the correlation-ID hop is kept consistent with the other services so MDC-correlated
 * logs line up.</p>
 *
 * <p>Provenance: [SRC: CORPT00C, CBSTM03A/B | TRANSACT] — app/csd/CARDDEMO.CSD
 * (CR00 -&gt; CORPT00C over file TRANSACT; batch statements CBSTM03A/B).</p>
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

    @Value("${carddemo.correlation.header:X-Correlation-ID}")
    private String headerName;

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
