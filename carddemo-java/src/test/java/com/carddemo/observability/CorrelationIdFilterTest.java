package com.carddemo.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

/**
 * Unit tests for {@link CorrelationIdFilter}.
 *
 * <p>Traceability (REFERENCE-ONLY; no COBOL equivalent; source commit {@code 27d6c6f}): exercises
 * the per-request correlation-id resolution strategy — UUID generation when the inbound header is
 * absent or blank, verbatim echo of a safe header, sanitization of disallowed characters,
 * truncation to the maximum length, and the guarantee that the MDC entry is populated for the
 * filter chain and removed afterwards so it cannot leak onto a reused thread.</p>
 */
@DisplayName("CorrelationIdFilter - per-request correlation id")
class CorrelationIdFilterTest {

    private static final String HEADER_NAME = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("Id resolution")
    class Resolution {

        @Test
        @DisplayName("Generates a UUID when the header is absent")
        void generatesUuidWhenAbsent() throws Exception {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(HEADER_NAME)).thenReturn(null);
            HttpServletResponse response = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(response).setHeader(eq(HEADER_NAME), captor.capture());
            String resolved = captor.getValue();
            assertThatCode(() -> UUID.fromString(resolved)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Generates a UUID when the header is blank")
        void generatesUuidWhenBlank() throws Exception {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(HEADER_NAME)).thenReturn("    ");
            HttpServletResponse response = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(response).setHeader(eq(HEADER_NAME), captor.capture());
            assertThatCode(() -> UUID.fromString(captor.getValue())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Echoes a safe inbound id verbatim")
        void echoesSafeId() throws Exception {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(HEADER_NAME)).thenReturn("abc-123_XYZ");
            HttpServletResponse response = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            verify(response).setHeader(HEADER_NAME, "abc-123_XYZ");
        }

        @Test
        @DisplayName("Strips characters outside [A-Za-z0-9_-]")
        void sanitizesUnsafeCharacters() throws Exception {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(HEADER_NAME)).thenReturn("abc@#$%def!");
            HttpServletResponse response = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            verify(response).setHeader(HEADER_NAME, "abcdef");
        }

        @Test
        @DisplayName("Truncates an over-length id to 64 characters")
        void truncatesLongId() throws Exception {
            String longId = "a".repeat(70);
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(HEADER_NAME)).thenReturn(longId);
            HttpServletResponse response = mock(HttpServletResponse.class);
            FilterChain chain = mock(FilterChain.class);

            filter.doFilterInternal(request, response, chain);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(response).setHeader(eq(HEADER_NAME), captor.capture());
            assertThat(captor.getValue()).hasSize(64);
        }
    }

    @Nested
    @DisplayName("MDC lifecycle")
    class MdcLifecycle {

        @Test
        @DisplayName("Publishes the id to the MDC during the chain and removes it afterwards")
        void publishesAndRemovesMdc() throws Exception {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader(HEADER_NAME)).thenReturn("trace-99");
            HttpServletResponse response = mock(HttpServletResponse.class);
            String[] duringChain = new String[1];
            FilterChain chain = (req, res) -> duringChain[0] = MDC.get(MDC_KEY);

            filter.doFilterInternal(request, response, chain);

            assertThat(duringChain[0]).isEqualTo("trace-99");
            assertThat(MDC.get(MDC_KEY)).isNull();
        }
    }
}
