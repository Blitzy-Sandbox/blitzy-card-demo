package com.carddemo.useradmin.config;

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
 * Servlet filter that establishes and propagates the correlation id for the
 * CardDemo User Administration + Admin Menu service ({@code useradmin-svc}).
 *
 * <p>This realizes the REAL correlation-ID hop UI -&gt; BFF -&gt; domain services even
 * though authentication is a permissive stub: the id is read from (or generated for)
 * every inbound request, placed into the SLF4J MDC under the key {@code correlationId}
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
