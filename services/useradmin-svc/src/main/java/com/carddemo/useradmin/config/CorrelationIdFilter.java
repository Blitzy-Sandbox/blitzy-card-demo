package com.carddemo.useradmin.config;

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
 * Servlet filter that establishes and propagates the correlation id for the
 * CardDemo User Administration + Admin Menu service ({@code useradmin-svc}).
 *
 * <p>This realizes the REAL correlation-ID hop UI -&gt; BFF -&gt; domain services even
 * though authentication is a permissive stub: the inbound id is trusted ONLY when it is a
 * bounded canonical UUID and a fresh one is minted otherwise (missing, blank, malformed,
 * over-long, or injection-bearing) so no unvalidated client input reaches the logs or the
 * response header (CWE-20), then placed into the SLF4J MDC under the key {@code correlationId}
 * (which the {@code %X{correlationId}} log pattern renders), and echoed back on the
 * response. The header name is read from the Spring property
 * {@code carddemo.correlation.header} (default {@code X-Correlation-ID}).
 *
 * <p>Provenance: [SRC: COUSR00C-03C, COADM01C | USRSEC] (topology only; infrastructure class).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

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

    private final String correlationHeader;

    public CorrelationIdFilter(
            @Value("${carddemo.correlation.header:X-Correlation-ID}") String correlationHeader) {
        this.correlationHeader = correlationHeader;
    }

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
