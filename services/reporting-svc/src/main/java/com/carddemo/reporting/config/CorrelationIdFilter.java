package com.carddemo.reporting.config;

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
 * The REAL correlation-ID hop for reporting-svc (CardDemo walking skeleton).
 *
 * <p>Runs once per request (extends {@link OncePerRequestFilter}) and is registered
 * automatically as a {@link Component} via the component scan rooted at
 * {@code ReportingApplication}. For every request it:</p>
 * <ol>
 *   <li>reads the correlation-ID header (name from {@code carddemo.correlation.header},
 *       default {@code X-Correlation-ID});</li>
 *   <li>generates a random UUID when the header is missing or blank;</li>
 *   <li>places the value into the SLF4J {@link MDC} under key {@code correlationId}
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

    @Value("${carddemo.correlation.header:X-Correlation-ID}")
    private String headerName;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(headerName);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(headerName, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
