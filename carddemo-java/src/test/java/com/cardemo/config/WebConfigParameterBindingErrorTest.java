/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Unit test for request-parameter binding error handling in WebConfig's advice
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test with NO COBOL source equivalent. It guards the
 *  resolution of QA FINAL ACCEPTANCE (ALT) finding F-VAL-1: a malformed
 *  pagination query parameter (e.g. ?page=abc, ?page=1.5, integer overflow) was
 *  surfaced as HTTP 500 INTERNAL_ERROR on the paginated list endpoints
 *  (GET /api/cards, /api/transactions, /api/admin/users) instead of the correct
 *  client-error 400. Root cause: MethodArgumentTypeMismatchException does NOT
 *  implement org.springframework.web.ErrorResponse in Spring Framework 6.2.x, so
 *  it fell through GlobalExceptionHandler#handleUnexpected's
 *  `instanceof ErrorResponse` guard to the 500 branch. The fix adds dedicated
 *  @ExceptionHandler methods (handleTypeMismatch, handleMissingParameter) that
 *  return 400 with the uniform ApiError envelope. Base package is com.cardemo
 *  (decision D-006).
 *
 *  This test wires the REAL production advice
 *  ({@link WebConfig.GlobalExceptionHandler}) onto a standalone MockMvc dispatcher
 *  whose probe controller mirrors the exact parameter binding of the three real
 *  list endpoints ({@code @RequestParam(defaultValue = "1") int page}). It thus
 *  exercises the genuine Spring MVC argument-resolution failure path that throws
 *  {@code MethodArgumentTypeMismatchException}/{@code MissingServletRequestParameterException}
 *  and routes it through the shipped advice — not a reimplementation. It needs
 *  neither a Spring context nor a database.
 * ============================================================================
 */
package com.cardemo.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies that {@link WebConfig.GlobalExceptionHandler} maps request-parameter binding failures to
 * <strong>HTTP&nbsp;400</strong> (carrying the uniform {@code ApiError} envelope with
 * {@code code=VALIDATION_FAILED}) rather than letting them fall through to a 500. This is the
 * regression guard for QA finding F-VAL-1.
 *
 * <p>The probe controller reproduces the binding of the three real paginated list endpoints
 * ({@code @RequestParam(defaultValue = "1") int page}) plus a required-parameter endpoint, so the
 * test drives the same {@code MethodArgumentTypeMismatchException} /
 * {@code MissingServletRequestParameterException} that the production controllers raise.</p>
 */
@DisplayName("WebConfig advice — request-parameter binding errors map to 400 (QA F-VAL-1)")
class WebConfigParameterBindingErrorTest {

    private MockMvc mockMvc;

    /**
     * A minimal stand-in controller whose parameter binding is identical to the real list endpoints
     * ({@code CardController}/{@code TransactionController}/{@code UserAdminController}) — a
     * {@code page} query parameter defaulting to 1 — plus a required parameter used to drive a
     * missing-parameter failure. It is intentionally tiny: the subject under test is the shipped
     * exception advice, not the controller.
     */
    @RestController
    static class ProbeController {

        /** Mirrors the real list-endpoint binding: a defaulted, type-converted {@code page} param. */
        @GetMapping("/probe/list")
        String list(@RequestParam(defaultValue = "1") final int page) {
            return "page=" + page;
        }

        /** A required (no-default) parameter, used to drive a missing-parameter failure. */
        @GetMapping("/probe/required")
        String required(@RequestParam final int size) {
            return "size=" + size;
        }
    }

    @BeforeEach
    void setUp() {
        // Build the message converter exactly the way Spring Boot does — by applying the REAL
        // production WebConfig customizer (JavaTimeModule, ISO date wiring) — so the ApiError record
        // (which carries an OffsetDateTime timestamp) serializes faithfully under standalone MockMvc.
        final Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new WebConfig(new String[] {"*"}).jacksonCustomizer().customize(builder);
        final ObjectMapper objectMapper = builder.build();
        final MappingJackson2HttpMessageConverter jacksonConverter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        // Wire the SHIPPED advice onto a standalone dispatcher so the genuine argument-resolution
        // failure path is exercised end-to-end and routed through the production @ExceptionHandlers.
        this.mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new WebConfig.GlobalExceptionHandler())
                .setMessageConverters(jacksonConverter)
                .build();
    }

    // ------------------------------------------------------------------------
    // The F-VAL-1 defect: malformed `page` previously produced 500; must be 400.
    // ------------------------------------------------------------------------

    @ParameterizedTest(name = "GET /probe/list?page={0} -> 400 (was 500)")
    @ValueSource(strings = {
            "abc",                       // the exact QA reproduction value (non-numeric)
            "1.5",                       // fractional — not an int (QA reproduction value)
            "99999999999999999999",      // exceeds Integer.MAX_VALUE — overflow (QA reproduction value)
            "12abc"                      // mixed alphanumeric — unambiguously not an int
    })
    @DisplayName("malformed page query parameter -> 400 VALIDATION_FAILED, never 500")
    void malformedPageParameterIsBadRequest(String badPage) throws Exception {
        mockMvc.perform(get("/probe/list").param("page", badPage)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("missing required parameter -> 400 VALIDATION_FAILED, never 500")
    void missingRequiredParameterIsBadRequest() throws Exception {
        mockMvc.perform(get("/probe/required").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ------------------------------------------------------------------------
    // Control cases: well-behaved numeric clients are unaffected by the fix.
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("valid numeric page -> 200 (well-behaved clients unaffected)")
    void validNumericPageIsOk() throws Exception {
        mockMvc.perform(get("/probe/list").param("page", "3")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("absent page (default applies) -> 200 (well-behaved clients unaffected)")
    void absentPageUsesDefaultAndIsOk() throws Exception {
        mockMvc.perform(get("/probe/list").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
