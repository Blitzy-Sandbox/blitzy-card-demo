package com.carddemo.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Focused tests for {@link CorrelationIdFilter}'s bounded correlation-ID normalization
 * (the shared card-svc policy now applied uniformly across the domain services).
 *
 * <p>Verifies the CWE-20 defense: a <em>canonical</em> inbound {@code X-Correlation-ID} is trusted
 * and echoed unchanged, while a <em>malformed</em> or injection-bearing value (e.g. one carrying
 * CR/LF for log- or response-header injection) is rejected and replaced with a freshly minted
 * canonical UUID, so no unvalidated client input reaches the SLF4J MDC or the echoed response
 * header. The filter's behavior is identical across auth/account/transaction/payment/useradmin
 * (byte-identical code); auth-svc stands in as the representative. Provenance:
 * {@code [SRC: COSGN00C | COSGN00.bms]}.</p>
 */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-ID";
    private static final Pattern CANONICAL = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final CorrelationIdFilter filter = new CorrelationIdFilter(HEADER);

    @Test
    void trustsCanonicalInboundUuid() throws ServletException, IOException {
        String canonical = "123e4567-e89b-12d3-a456-426614174000";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, canonical);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        // A canonical inbound id is propagated unchanged (the tracer's UI/BFF-supplied id).
        assertThat(response.getHeader(HEADER)).isEqualTo(canonical);
    }

    @Test
    void rejectsMalformedInbound_andMintsFreshCanonicalUuid() throws ServletException, IOException {
        // A non-canonical value carrying CR/LF (a classic log-/header-injection attempt).
        String malicious = "not-a-uuid\r\nSet-Cookie: evil=1";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, malicious);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(HEADER);
        // The malicious value is NOT echoed; a fresh canonical UUID is minted instead.
        assertThat(echoed).isNotEqualTo(malicious);
        assertThat(echoed).matches(CANONICAL);
    }
}
