package com.carddemo.account.config;

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
 * The REAL correlation-ID hop for {@code account-svc} (CardDemo walking skeleton).
 *
 * <p>Runs exactly once per request (extends {@link OncePerRequestFilter}) and is
 * registered automatically as a {@link Component} via the component scan rooted at
 * {@code AccountApplication} &mdash; no explicit servlet-filter registration is
 * required. For every inbound request it:</p>
 * <ol>
 *   <li>reads the correlation-ID header (name from the {@code correlation.header}
 *       property, defaulting to {@code X-Correlation-ID});</li>
 *   <li>generates a random {@link UUID} when the header is missing or blank
 *       (resilient first-hop behavior);</li>
 *   <li>places the value into the SLF4J {@link MDC} under key {@code correlationId}
 *       (rendered by the {@code application.yml} log pattern {@code %X{correlationId:-}});</li>
 *   <li>echoes the value back on the response header so callers/tests observe propagation;</li>
 *   <li>clears the MDC key in a {@code finally} block for thread-pool safety.</li>
 * </ol>
 *
 * <p>Authentication in the skeleton is a permissive stub, but this correlation hop is
 * genuine (AAP &sect;0.4 / &sect;0.8): the identifier propagates UI -&gt; BFF -&gt; account-svc
 * so a single request can be traced across every service boundary via the SLF4J MDC. In
 * the tracer the UI/BFF supply the id, which is then propagated unchanged; when absent a
 * new id is minted here as the first hop.</p>
 *
 * <p>The {@code finally}-block cleanup is mandatory: Tomcat serves requests from a pooled
 * worker thread, so a value left in the MDC would bleed into the next, unrelated request
 * handled by the same thread.</p>
 *
 * <p><strong>Convention note:</strong> the account-svc header property key is
 * {@code correlation.header} (unprefixed), matching the sibling {@code application.yml};
 * this intentionally differs from the prefixed variant used by some other services. The
 * MDC key is always the literal {@code correlationId}.</p>
 *
 * <p>Provenance: [SRC: COACTVWC/COACTUPC | ACCTDAT] &mdash; app/csd/CARDDEMO.CSD
 * ({@code DEFINE PROGRAM(COACTVWC)} / {@code TRANSID(CAVW)}, "Accept and process Account
 * View request", and {@code DEFINE PROGRAM(COACTUPC)} / {@code TRANSID(CAUP)}, "Accept and
 * process ACCOUNT UPDATE") over {@code DEFINE FILE(ACCTDAT)}
 * (DSNAME {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}); account-svc is the modern account
 * bounded context. The legacy references are provenance/topology ONLY &mdash; this
 * cross-cutting infrastructure is not dictated by COBOL logic and no COBOL behavior is
 * ported.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** MDC key printed by the {@code application.yml} log pattern {@code %X{correlationId:-}}. */
    private static final String MDC_KEY = "correlationId";

    /**
     * Name of the HTTP header that carries the correlation ID. Sourced from the account-svc
     * {@code correlation.header} property (overridable via the {@code CORRELATION_HEADER}
     * environment variable) and defaulting to {@code X-Correlation-ID}, keeping the header
     * consistent across every service in the topology.
     */
    private final String correlationHeader;

    /**
     * Injects the configurable correlation-ID header name.
     *
     * @param correlationHeader the HTTP header name used to read and echo the correlation
     *                          ID; bound from the {@code correlation.header} property with an
     *                          {@code X-Correlation-ID} default
     */
    public CorrelationIdFilter(
            @Value("${correlation.header:X-Correlation-ID}") String correlationHeader) {
        this.correlationHeader = correlationHeader;
    }

    /**
     * Establishes the correlation ID for the current request, publishes it to the SLF4J
     * {@link MDC} and the response, then guarantees MDC cleanup.
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
