package com.carddemo.unit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.observability.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link CorrelationIdFilter}. Verifies correlation-ID resolution
 * (passthrough, generation, sanitization, truncation, and illegal-only
 * fallback), that the value is visible in the SLF4J MDC for the duration of the
 * downstream chain, that it is echoed on the response header, and that the MDC
 * entry is always removed after the request completes.
 */
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";
    private static final String UUID_REGEX =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Runs the filter, capturing the MDC value observed during the chain. */
    private String[] invoke(MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        final String[] seenDuringChain = new String[1];
        FilterChain chain = (req, res) -> seenDuringChain[0] = MDC.get(MDC_KEY);
        filter.doFilter(request, response, chain);
        return seenDuringChain;
    }

    @Test
    @DisplayName("absent header -> a fresh UUID is generated, exposed in MDC and response header")
    void generatesUuidWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = invoke(request, response);

        assertThat(seen[0]).matches(UUID_REGEX);
        assertThat(response.getHeader(HEADER)).isEqualTo(seen[0]);
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("valid header value passes through unchanged")
    void validHeaderPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cards");
        request.addHeader(HEADER, "abc-123_XYZ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = invoke(request, response);

        assertThat(seen[0]).isEqualTo("abc-123_XYZ");
        assertThat(response.getHeader(HEADER)).isEqualTo("abc-123_XYZ");
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("injection characters are stripped before reaching MDC and response")
    void sanitizesInjectionCharacters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cards");
        request.addHeader(HEADER, "ab\ncd\r\nGET /evil");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = invoke(request, response);

        assertThat(seen[0]).isEqualTo("abcdGETevil");
        assertThat(seen[0]).doesNotContain("\n").doesNotContain("\r").doesNotContain(" ");
        assertThat(response.getHeader(HEADER)).isEqualTo("abcdGETevil");
    }

    @Test
    @DisplayName("overly long header values are truncated to 64 characters")
    void truncatesLongHeader() throws Exception {
        String longValue = "a".repeat(100);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cards");
        request.addHeader(HEADER, longValue);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = invoke(request, response);

        assertThat(seen[0]).hasSize(64);
        assertThat(seen[0]).isEqualTo("a".repeat(64));
    }

    @Test
    @DisplayName("header with only illegal characters falls back to a generated UUID")
    void illegalOnlyHeaderFallsBackToUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cards");
        request.addHeader(HEADER, "@@@ /// !!!");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seen = invoke(request, response);

        assertThat(seen[0]).matches(UUID_REGEX);
        assertThat(response.getHeader(HEADER)).isEqualTo(seen[0]);
    }
}
