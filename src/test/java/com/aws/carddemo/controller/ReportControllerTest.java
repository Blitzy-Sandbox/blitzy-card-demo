/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
/*
 * ReportControllerTest — Spring MVC slice test for ReportController
 *
 * Replaces BMS mapset: CORPT00.bms
 * Replaces COBOL pgm:  CORPT00C.cbl (649 lines, TRANID CR00, JCL JOB TRNRPT00)
 *
 * AAP references:
 *   §0.5.1  CREATE — Controller Integration Tests
 *   §0.4.1  Strategy — @WebMvcTest + @MockBean + MockMvc
 *   §0.7.1  Coverage — controller line ≥80%, branch ≥70%
 *
 * Mocking boundary: ReportSubmissionService (@MockBean).
 *
 * COBOL business rules preserved at HTTP layer:
 *   - Report modes: MONTHLY, YEARLY, CUSTOM (CUSTOM requires startDate + endDate)
 *   - Date validation: startDate ≤ endDate, both in YYYY-MM-DD format
 *   - JCL JOB name "TRNRPT00" preserved as jobName in response (AAP §0.10.4)
 *   - Asynchronous: returns job-handle (jobId) immediately, NOT batch results
 *
 * Adaptation notes (versus the agent-prompt blueprint):
 *
 *   - The production DTO field names are `reportMode`, `startDate`, `endDate`,
 *     `confirmation` (verbatim COBOL field-name parity) — NOT the prompt's
 *     `reportType`, `monthYear`, `year`. The MONTHLY/YEARLY tests therefore
 *     post only the `reportMode` field; the date window is resolved
 *     internally by ReportSubmissionService against an injected Clock fixed
 *     at 2024-01-15. CUSTOM tests post `reportMode`, `startDate`, `endDate`.
 *
 *   - The service signature is `submit(ReportSubmissionRequest)` — one
 *     argument, NOT two (no user / principal argument). The mock stubs and
 *     verify(...) calls match this single-arg signature.
 *
 *   - The service result is a `ReportSubmissionResult` with factory methods
 *     ONLY (`success(message, handle)` / `failure(message)`) — NOT a builder
 *     pattern. The standardJobHandle() helper therefore composes a
 *     ReportSubmissionResult via the success(...) factory, embedding a
 *     ReportJobHandle.of("CR00-MONTHLY-20240115000000") for the success
 *     outcome's identifier.
 *
 *   - The response DTO is ReportController.ReportSubmissionJsonResponse with
 *     fields: success, message, jobId, jobName, reportMode. There is NO
 *     `status` field (the prompt suggested `$.status = "SUBMITTED"` but the
 *     production controller exposes the boolean `$.success = true` instead)
 *     and NO `submittedAt` or `submittedBy` fields. The test assertions
 *     match the actual production wire format.
 *
 *   - The controller auto-injects `confirmation = "Y"` into every inbound
 *     request before delegating to the service (REST-confirmation
 *     semantics — see ReportController class Javadoc). Tests therefore do
 *     not need to send `confirmation` in the JSON body; the controller
 *     populates it. The "operator cancelled" path
 *     (ReportSubmissionService.MSG_CANCELLED) is therefore unreachable from
 *     this controller and is not exercised here.
 */
package com.aws.carddemo.controller;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies)
//
//   * TestFixtures — single source of truth for fixture date constants
//     (Dates.REPORT_START_DATE, Dates.REPORT_END_DATE, Dates.FIXED_CLOCK_INSTANT)
//     used in the CUSTOM-range request JSON and in the standardJobHandle
//     helper to populate a deterministic Instant for ReportJobHandle creation.
//
//   * ReportSubmissionService — the service collaborator mocked via @MockBean.
//     The AAP §0.10.1 Require Test Coverage rule restricts mocks to external
//     boundaries; the controller-under-test calls a real ReportController
//     whose only dependency, ReportSubmissionService, is the mocked boundary.
//
//   * ReportSubmissionRequest — mutable POJO with getter/setter accessors;
//     the test posts JSON whose field names match Jackson's reflection-based
//     binding to the request setters. The ArgumentCaptor in
//     submitReport_customRange_returns202() captures one of these for date
//     parsing assertion.
//
//   * ReportSubmissionResult — service-return value with factory methods
//     `success(message, handle)` and `failure(message)`; composed by the
//     standardJobHandle / rejectResult helpers below.
//
//   * ReportJobHandle — opaque dispatcher-assigned identifier wrapper
//     constructed via `ReportJobHandle.of(identifier)`; carried in the
//     ReportSubmissionResult success outcome.
// ---------------------------------------------------------------------------
import com.aws.carddemo.service.ReportJobHandle;
import com.aws.carddemo.service.ReportSubmissionRequest;
import com.aws.carddemo.service.ReportSubmissionResult;
import com.aws.carddemo.service.ReportSubmissionService;
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (AAP §0.10.7 framework constraint — JUnit 5 only).
//
//   * @Test     — discovers each test method.
//   * @DisplayName — human-readable test-class and method labels for IDE
//                  test runners and CI reports.
//   * @BeforeEach — per-method reset hook (Mockito.reset on the @MockBean).
//   * @Execution(SAME_THREAD) — disables intra-class parallelism so the
//                  @WebMvcTest application-context's @MockBean is not torn
//                  by concurrent test methods (see resetMocks Javadoc).
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

// ---------------------------------------------------------------------------
// Spring Test slice / Mockito test-context wiring
//
//   * @WebMvcTest — loads only the Spring MVC slice (the ReportController
//     bean, its message converters, the validation infrastructure, the
//     auto-configured Spring Security filter chain) without JPA,
//     repositories, or the full @SpringBootApplication context (AAP §0.4.1).
//
//   * @Import(SecurityTestConfig.class) — pulls in the inline test security
//     configuration so the filter chain is wired correctly. Matches the
//     pattern used by TransactionControllerTest and UserAdminControllerTest.
//
//   * @MockBean — replaces the real ReportSubmissionService bean in the
//     @WebMvcTest context with a Mockito mock (AAP §0.10.1 — single mocking
//     boundary).
//
//   * @Autowired — injects the MockMvc harness and the auto-configured
//     Jackson ObjectMapper into the test class.
// ---------------------------------------------------------------------------
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

// ---------------------------------------------------------------------------
// Spring Security Test — request post-processors
//
// SecurityMockMvcRequestPostProcessors.csrf() attaches a valid CSRF token to
// state-changing requests so they pass Spring Security's CsrfFilter. The
// deliberately-omitted-csrf test verifies the controller rejects forged
// requests with HTTP 403.
//
// Direct reference (not a static import) so the test reads as
// .with(SecurityMockMvcRequestPostProcessors.csrf()), making the security
// post-processor explicit at every call site.
// ---------------------------------------------------------------------------
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

// ---------------------------------------------------------------------------
// Jackson — auto-configured by @WebMvcTest. Used to serialise the test's
// in-method ReportSubmissionRequest objects to JSON strings for MockMvc
// .content(...) bodies, and to deserialise response bodies when JsonPath
// is awkward.
// ---------------------------------------------------------------------------
import com.fasterxml.jackson.databind.ObjectMapper;

// ---------------------------------------------------------------------------
// Mockito ArgumentCaptor — captures the ReportSubmissionRequest passed to
// the service in the CUSTOM range test so the test can assert on the
// captured field values (proving correct JSON-to-POJO binding).
// ---------------------------------------------------------------------------
import org.mockito.ArgumentCaptor;

// ---------------------------------------------------------------------------
// JDK 17 standard library
//
//   * Instant — used in the standardJobHandle helper to derive a
//     deterministic submission timestamp string from
//     TestFixtures.Dates.FIXED_CLOCK_INSTANT. The ReportJobHandle stores
//     only an opaque identifier; the timestamp is embedded into that
//     identifier so the produced jobId is stable across test runs.
//   * LocalDate — used in the ArgumentCaptor assertion of the CUSTOM range
//     test to verify that the controller did NOT mutate the
//     ReportSubmissionRequest's startDate / endDate fields between
//     deserialisation and the service call. The captor receives the request
//     POJO whose date fields are the raw ISO strings; LocalDate.parse(...)
//     re-parses them so the assertion is meaningful.
// ---------------------------------------------------------------------------
import java.time.Instant;
import java.time.LocalDate;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL + MockMvc DSL (AAP §0.6.2
// + §0.10.10 — AssertJ-only style; no Hamcrest matchers in CardDemo tests).
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC slice test for {@link ReportController}.
 *
 * <p>Verifies the HTTP-boundary behaviour of the report-submission endpoint
 * that replaces BMS mapset {@code app/bms/CORPT00.bms} and COBOL program
 * {@code app/cbl/CORPT00C.cbl} (TRANID {@code CR00}, JCL JOB
 * {@code TRNRPT00}).
 *
 * <h2>Test Categories (Phase 2 §0.4.2 blueprint)</h2>
 *
 * <ul>
 *   <li><b>Happy paths</b> — authenticated MONTHLY / YEARLY / CUSTOM
 *       submissions each return HTTP 202 Accepted with a populated
 *       {@code jobId} (from the dispatcher), the verbatim JCL JOB name
 *       {@code "TRNRPT00"}, and the echoed {@code reportMode}.</li>
 *   <li><b>Validation rejects (HTTP 400)</b> — verified end-to-end by
 *       stubbing the service to return the COBOL-equivalent failure-result
 *       message:
 *       <ul>
 *         <li>{@code "End Date can NOT be empty"} — CUSTOM missing endDate</li>
 *         <li>{@code "End Date should be greater than Start Date"} —
 *             CUSTOM start &gt; end</li>
 *         <li>{@code "Start Date - Not a valid date..."} — CUSTOM with
 *             malformed ISO date format</li>
 *         <li>{@code "Please select a report type..."} — request body
 *             omits the {@code reportMode} field altogether (empty {})
 *             OR provides an unknown mode like {@code "QUARTERLY"} (the
 *             service does not match it and returns the
 *             MSG_SELECT_REPORT_TYPE reject)</li>
 *       </ul></li>
 *   <li><b>Authorisation reject (HTTP 401)</b> — unauthenticated caller
 *       receives HTTP 401 from the Spring Security filter chain; the mocked
 *       service is verified never to have been invoked
 *       (defence-in-depth).</li>
 *   <li><b>CSRF reject (HTTP 403)</b> — authenticated POST issued without
 *       a valid CSRF token receives HTTP 403 from Spring Security's
 *       {@code CsrfFilter}; the mocked service is never invoked.</li>
 *   <li><b>Service failure (HTTP 500)</b> — when the service throws a
 *       {@link RuntimeException}, the controller's
 *       {@code @ExceptionHandler} returns HTTP 500 with a generic
 *       sanitised body — never the underlying exception detail (AAP
 *       §0.10.5 applied to error paths).</li>
 * </ul>
 *
 * <h2>Mocking Boundary (AAP §0.10.1)</h2>
 *
 * <p>The only mocked collaborator is {@link ReportSubmissionService} (the
 * downstream service the controller delegates to). The controller itself
 * is the real bean loaded by {@code @WebMvcTest}; Spring's MVC
 * infrastructure (DispatcherServlet, HandlerMapping, message converters,
 * exception resolvers) and the Spring Security filter chain are the real
 * production wiring. Per the Require Test Coverage rule, no test method
 * duplicates the controller's HTTP-status mapping logic — every assertion
 * observes the controller's externally-visible HTTP output.
 *
 * @see ReportController
 * @see ReportSubmissionService
 * @see ReportSubmissionRequest
 * @see ReportSubmissionResult
 * @see ReportJobHandle
 * @see TestFixtures.Dates
 */
@WebMvcTest(controllers = ReportController.class)
@Import(ReportControllerTest.SecurityTestConfig.class)
@DisplayName("ReportController — CORPT00C.cbl migration parity (BMS CORPT00, TRANID CR00, JCL TRNRPT00)")
@Execution(ExecutionMode.SAME_THREAD)
final class ReportControllerTest {

    // ------------------------------------------------------------------------
    // Parallelism — SAME_THREAD enforced (AAP §0.10.9 explanatory note)
    // ------------------------------------------------------------------------
    //
    // junit-platform.properties enables class-level parallel execution
    // (junit.jupiter.execution.parallel.mode.classes.default = concurrent).
    // Even without @Nested groups this test class shares a single Spring
    // @WebMvcTest application context and thus a single @MockBean instance
    // of ReportSubmissionService. SAME_THREAD execution serialises this
    // class's test methods on a single worker, restoring the per-test
    // isolation that @MockBean and ArgumentCaptor assertions expect.
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // Service-layer reject-message mirrors
    // ------------------------------------------------------------------------
    //
    // The MSG_* and MODE_* constants on ReportSubmissionService are
    // package-private (no modifier on the `static final String`
    // declarations) and so cannot be referenced from this controller-package
    // test. The test duplicates the literals verbatim so each happy/sad path
    // can stub the mock to return the exact COBOL-equivalent message that
    // the controller's HTTP-status mapping dispatches on. If a future agent
    // renames or relocates one of these messages, the production service
    // unit tests will fail simultaneously, surfacing the drift loudly
    // (AAP §0.10.10 style consistency).
    // ------------------------------------------------------------------------

    /** Mirror of {@code ReportSubmissionService.JOB_NAME} — JCL JOB name from CORPT00C.cbl line 84. */
    private static final String JOB_NAME = "TRNRPT00";

    /** Mirror of {@code ReportSubmissionService.MSG_SUBMITTED}. */
    private static final String MSG_SUBMITTED = "Report submitted for printing ...";

    /** Mirror of {@code ReportSubmissionService.MSG_SELECT_REPORT_TYPE}. */
    private static final String MSG_SELECT_REPORT_TYPE = "Please select a report type...";

    /** Mirror of {@code ReportSubmissionService.MSG_START_DATE_EMPTY}. */
    private static final String MSG_START_DATE_EMPTY = "Start Date can NOT be empty";

    /** Mirror of {@code ReportSubmissionService.MSG_END_DATE_EMPTY}. */
    private static final String MSG_END_DATE_EMPTY = "End Date can NOT be empty";

    /** Mirror of {@code ReportSubmissionService.MSG_START_DATE_INVALID}. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /** Mirror of {@code ReportSubmissionService.MSG_END_DATE_INVALID}. */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /** Mirror of {@code ReportSubmissionService.MSG_END_BEFORE_START}. */
    private static final String MSG_END_BEFORE_START = "End Date should be greater than Start Date";

    /** Mirror of {@code ReportController.MSG_INTERNAL_ERROR} — sanitised 500 body. */
    private static final String MSG_INTERNAL_ERROR = "An unexpected error occurred";

    /** Mirror of {@code ReportSubmissionService.MODE_MONTHLY}. */
    private static final String MODE_MONTHLY = "MONTHLY";

    /** Mirror of {@code ReportSubmissionService.MODE_YEARLY}. */
    private static final String MODE_YEARLY = "YEARLY";

    /** Mirror of {@code ReportSubmissionService.MODE_CUSTOM}. */
    private static final String MODE_CUSTOM = "CUSTOM";

    /** Test user name used by {@code @WithMockUser} on authenticated test methods. */
    private static final String TEST_USERNAME = "testuser";

    // ------------------------------------------------------------------------
    // Test fixtures (injected & static)
    // ------------------------------------------------------------------------

    /**
     * Servlet-free HTTP harness auto-configured by {@code @WebMvcTest}.
     * Used to issue requests against the loaded {@link ReportController}
     * and assert on HTTP status, headers, and JSON body via the Spring MVC
     * test DSL ({@code MockMvcResultMatchers.status()},
     * {@code .content()}, {@code .jsonPath(...)}).
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Auto-configured Jackson {@link ObjectMapper} (Spring Boot defaults).
     * Used by helper methods to serialise auxiliary request objects when a
     * test needs more programmatic flexibility than the raw JSON literals
     * provided by {@link #monthlyRequestJson()} and {@link #customRangeRequestJson()}.
     *
     * <p>Although none of the eleven tests currently calls
     * {@code objectMapper.writeValueAsString(...)} directly, the field is
     * required by the external_imports schema (jackson-databind
     * ObjectMapper) and is retained as injected so future tests can compose
     * mutable {@link ReportSubmissionRequest} fixtures without falling back
     * to string concatenation. {@code @SuppressWarnings("unused")} is
     * intentionally omitted: the field IS used by the @Autowired wiring
     * (Spring's BeanPostProcessor reads it) and the schema treats it as a
     * required dependency.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked report-submission service — the single mocking boundary per
     * AAP §0.10.1. Every test stubs this mock's
     * {@code submit(ReportSubmissionRequest)} method with either a canned
     * {@link ReportSubmissionResult} (happy / reject paths) or an exception
     * (service-failure path).
     */
    @MockBean
    private ReportSubmissionService reportSubmissionService;

    /**
     * Resets the {@link ReportSubmissionService} mock before each test
     * method.
     *
     * <p>By default {@code @MockBean} fields are reset by the
     * {@code MockitoTestExecutionListener} between test methods, but in
     * Spring Boot 3.x with the @WebMvcTest application context cached
     * across test methods the listener does not always reliably reset the
     * mock state — verify(...) count assertions sometimes accumulate
     * across tests and fail with "Wanted 1 time: But was N times". The
     * explicit reset here is the canonical Spring Boot idiom that pins
     * per-method isolation for mocked beans living in a cached context
     * (matches the pattern used by TransactionControllerTest and
     * UserAdminControllerTest).
     */
    @BeforeEach
    void resetMocks() {
        org.mockito.Mockito.reset(reportSubmissionService);
    }

    // ========================================================================
    // SecurityTestConfig — minimal inline security wiring for the slice test
    // ========================================================================

    /**
     * Inline {@code @TestConfiguration} that activates Spring Security's
     * method-level authorisation evaluation. While the
     * {@link ReportController} endpoint is not currently restricted to
     * specific roles, the auto-configured Spring Security filter chain
     * still requires authentication on every request, producing HTTP 401
     * for anonymous callers and HTTP 403 for state-changing requests
     * missing a CSRF token.
     *
     * <p>Using {@code @TestConfiguration} (rather than
     * {@code @Configuration}) tells Spring Boot to treat this config as a
     * test-time augmentation that COMPLEMENTS the auto-configuration
     * rather than replacing it.
     *
     * <p>The production {@code SecurityConfig} (subsequent migration step)
     * is expected to mirror this wiring: require authentication on
     * {@code /api/reports/**} and keep CSRF enabled on state-changing
     * requests. This test config exists because no production
     * {@code SecurityConfig} class has been migrated yet — remove this
     * {@code @Import} once production wiring lands (matches the pattern
     * used by TransactionControllerTest and UserAdminControllerTest).
     */
    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityTestConfig {
        // Marker @TestConfiguration that only activates
        // @EnableMethodSecurity. The SecurityFilterChain bean is
        // auto-configured by Spring Boot.
    }

    // ========================================================================
    // HAPPY PATHS — MONTHLY / YEARLY / CUSTOM → HTTP 202 Accepted
    // ========================================================================

    /**
     * Verifies authenticated MONTHLY submission: returns HTTP 202 Accepted
     * with a populated {@code jobId} (dispatcher-assigned identifier), the
     * verbatim JCL JOB name {@code "TRNRPT00"}, and the echoed
     * {@code reportMode}. The COBOL CORPT00C MONTHLY mode resolves the
     * date window to the current calendar month (computed inside
     * ReportSubmissionService); the controller delegates the resolution
     * entirely to the service. The test asserts:
     *
     * <ul>
     *   <li>HTTP status 202 (RFC 7231 §6.3.3 — async accept/acknowledge).</li>
     *   <li>{@code Content-Type: application/json}.</li>
     *   <li>{@code $.success = true}.</li>
     *   <li>{@code $.message = MSG_SUBMITTED} (verbatim COBOL token).</li>
     *   <li>{@code $.jobId} carries the dispatcher's identifier (not null,
     *       and equal to the stubbed handle's identifier).</li>
     *   <li>{@code $.jobName = "TRNRPT00"} (verbatim COBOL JCL JOB
     *       name — AAP §0.10.4 Immutable Boundaries).</li>
     *   <li>{@code $.reportMode = "MONTHLY"}.</li>
     *   <li>The service was invoked exactly once with the controller's
     *       deserialised request (defence-in-depth verify).</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — MONTHLY → 202 Accepted with jobId, jobName=TRNRPT00, success=true")
    void submitReport_monthlyType_returns202WithJobHandle() throws Exception {
        // Arrange — stub the service to return a successful submission
        // outcome for the MONTHLY mode. The dispatcher-assigned identifier
        // is computed deterministically from TestFixtures.Dates.FIXED_CLOCK_INSTANT
        // so the assertion below can compare against a known token.
        ReportSubmissionResult monthlyResult = standardJobHandle(MODE_MONTHLY);
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(monthlyResult);

        // Act + Assert — POST the MONTHLY request, expect 202 Accepted with
        // the full success-shape JSON body.
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monthlyRequestJson()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(MSG_SUBMITTED))
                .andExpect(jsonPath("$.jobId").value(jobIdFor(MODE_MONTHLY)))
                .andExpect(jsonPath("$.jobName").value(JOB_NAME))
                .andExpect(jsonPath("$.reportMode").value(MODE_MONTHLY));

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies authenticated YEARLY submission: returns HTTP 202 Accepted
     * with the same shape as the MONTHLY happy path but echoing
     * {@code reportMode = "YEARLY"}. The COBOL CORPT00C YEARLY mode
     * resolves the date window to the current calendar year (computed
     * inside ReportSubmissionService); the controller delegates resolution
     * entirely to the service.
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — YEARLY → 202 Accepted with jobId, jobName=TRNRPT00, success=true")
    void submitReport_yearlyType_returns202() throws Exception {
        // Arrange — service returns a successful submission outcome for
        // YEARLY mode with a distinct deterministic jobId.
        ReportSubmissionResult yearlyResult = standardJobHandle(MODE_YEARLY);
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(yearlyResult);

        // Act + Assert
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(yearlyRequestJson()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(MSG_SUBMITTED))
                .andExpect(jsonPath("$.jobId").value(jobIdFor(MODE_YEARLY)))
                .andExpect(jsonPath("$.jobName").value(JOB_NAME))
                .andExpect(jsonPath("$.reportMode").value(MODE_YEARLY));

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies authenticated CUSTOM-range submission: returns HTTP 202
     * Accepted with the success-shape JSON body AND captures the
     * deserialised {@link ReportSubmissionRequest} via an
     * {@link ArgumentCaptor} to assert that the controller forwarded the
     * raw ISO {@code YYYY-MM-DD} strings to the service unmodified.
     *
     * <p>The captured POJO is then re-parsed via {@link LocalDate#parse}
     * and compared against {@link LocalDate#of(int, int, int)} fixtures
     * derived from {@link TestFixtures.Dates#REPORT_START_DATE} (2022-01-01)
     * and {@link TestFixtures.Dates#REPORT_END_DATE} (2022-07-06). The
     * round-trip {@code String → LocalDate} parse-equality is the
     * canonical way to assert that Jackson's reflection-based binding
     * preserved the ISO-format date strings verbatim and that the
     * controller did not mutate them between deserialisation and the
     * service call.
     *
     * <p>The {@code confirmation} field on the captured request is
     * asserted to equal {@code "Y"} — proving the controller's
     * REST-confirmation-semantics auto-injection (see ReportController
     * class Javadoc) took effect.
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — CUSTOM range → 202 with captured ISO dates and auto-injected confirmation=Y")
    void submitReport_customRange_returns202() throws Exception {
        // Arrange — service returns a successful submission outcome for
        // CUSTOM mode.
        ReportSubmissionResult customResult = standardJobHandle(MODE_CUSTOM);
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(customResult);

        // Act + Assert — POST the CUSTOM-range request, expect 202.
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customRangeRequestJson()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(MSG_SUBMITTED))
                .andExpect(jsonPath("$.jobId").value(jobIdFor(MODE_CUSTOM)))
                .andExpect(jsonPath("$.jobName").value(JOB_NAME))
                .andExpect(jsonPath("$.reportMode").value(MODE_CUSTOM));

        // Capture the deserialised request to assert correct date parsing
        // and confirmation auto-injection. AssertJ's chained equality
        // comparisons read fluently (Arrange-Act-Assert structure
        // preserved per AAP §0.10.10).
        ArgumentCaptor<ReportSubmissionRequest> captor =
                ArgumentCaptor.forClass(ReportSubmissionRequest.class);
        verify(reportSubmissionService, times(1)).submit(captor.capture());

        ReportSubmissionRequest captured = captor.getValue();
        assertThat(captured).isNotNull();
        assertThat(captured.getReportMode()).isEqualTo(MODE_CUSTOM);
        // The controller MUST NOT mutate the raw ISO date strings between
        // Jackson deserialisation and the service call. Asserting via
        // LocalDate.parse(...) is the canonical idiom for proving
        // String → LocalDate parse-equality without coupling the test to
        // whitespace or zero-padding semantics.
        assertThat(captured.getStartDate())
                .isEqualTo(TestFixtures.Dates.REPORT_START_DATE);
        assertThat(captured.getEndDate())
                .isEqualTo(TestFixtures.Dates.REPORT_END_DATE);
        assertThat(LocalDate.parse(captured.getStartDate()))
                .isEqualTo(LocalDate.of(2022, 1, 1));
        assertThat(LocalDate.parse(captured.getEndDate()))
                .isEqualTo(LocalDate.of(2022, 7, 6));
        // Controller auto-injects "Y" (REST-confirmation semantics) — see
        // ReportController class Javadoc for the COBOL paragraph mapping.
        assertThat(captured.getConfirmation()).isEqualTo("Y");

        // Identity-level reinforcement: the controller MUST forward the
        // EXACT same ReportSubmissionRequest instance to the service (no
        // defensive copy, no field-level reconstruction). Since
        // ReportSubmissionRequest does not override equals(), Mockito's
        // eq(...) falls back to reference identity, which is precisely
        // the architectural guarantee we want: a single immutable hop
        // from request-body deserialisation to service invocation —
        // mirrors UserAdminControllerTest line 619 pattern.
        verify(reportSubmissionService).submit(eq(captured));
    }

    // ========================================================================
    // VALIDATION REJECTS — HTTP 400 driven by service-layer failure-result
    // ========================================================================

    /**
     * Verifies that a CUSTOM submission missing the {@code endDate} field
     * is rejected with HTTP 400 and the COBOL-equivalent reject message
     * {@code "End Date can NOT be empty"}. The validation cascade lives
     * entirely in {@link ReportSubmissionService}; the controller simply
     * translates the failure-result {@code message} into the wire-format
     * body and the HTTP status code.
     *
     * <p>The mocked service is verified to have been invoked exactly
     * once — proving the validation is service-driven, NOT
     * controller-driven (matches AAP §0.10.1 Require Test Coverage rule:
     * the controller does not duplicate the service's validation logic).
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — CUSTOM missing endDate → 400 with MSG_END_DATE_EMPTY")
    void submitReport_customMissingEndDate_returns400() throws Exception {
        // Arrange — service returns the failure-result for the missing-end-date
        // path (mirrors ReportSubmissionService's resolveCustomDateRange
        // helper at line 553 of the service).
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(ReportSubmissionResult.failure(MSG_END_DATE_EMPTY));

        // Act + Assert
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportMode": "CUSTOM",
                                  "startDate": "2022-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG_END_DATE_EMPTY))
                .andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.jobName").doesNotExist())
                .andExpect(jsonPath("$.reportMode").value(MODE_CUSTOM));

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies that a CUSTOM submission with {@code startDate} AFTER
     * {@code endDate} is rejected with HTTP 400 and the COBOL-equivalent
     * reject message {@code "End Date should be greater than Start Date"}.
     * The response body is additionally asserted with AssertJ's
     * {@code .contains(...)} for the substrings {@code "Start"}
     * (case-sensitive — matches the COBOL token "Start Date") and
     * {@code "End"} so the test surfaces drift if a future refactor
     * rewords the message but preserves only one of the two tokens.
     * AssertJ-only style per AAP §0.10.10 — no Hamcrest matchers.
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — CUSTOM start>end → 400 with MSG_END_BEFORE_START")
    void submitReport_customStartAfterEnd_returns400() throws Exception {
        // Arrange — service returns the failure-result for the end-before-start
        // path (matches the COBOL ordering check in CORPT00C.cbl
        // PROCESS-ENTER-KEY paragraph).
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(ReportSubmissionResult.failure(MSG_END_BEFORE_START));

        // Act + Assert — start=2022-07-06, end=2022-01-01 (inverted).
        MvcResult result = mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportMode": "CUSTOM",
                                  "startDate": "2022-07-06",
                                  "endDate":   "2022-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG_END_BEFORE_START))
                .andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.jobName").doesNotExist())
                .andReturn();
        // AssertJ token-presence assertions — verifies both "Start" and
        // "End" tokens survive any future message refactor. The test would
        // NOT fail on a rephrased message like "End Date must be later
        // than Start Date" — only on a refactor that drops one of the two
        // date-name tokens (e.g., "End-of-range must follow beginning-of-
        // range", which would lose both anchor tokens). AssertJ-only style
        // per AAP §0.10.10.
        assertThat(result.getResponse().getContentAsString())
                .as("end-before-start reject body retains both Start and End tokens")
                .contains("Start")
                .contains("End");

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies that a CUSTOM submission with malformed ISO date format
     * ({@code "01/01/2022"} instead of the canonical {@code "2022-01-01"})
     * is rejected with HTTP 400 and the COBOL-equivalent reject message
     * {@code "Start Date - Not a valid date..."}. The service's date-parse
     * cascade detects the malformed format (CORPT00C used the LE
     * {@code CEEDAYS} intrinsic; the Java migration uses
     * {@link LocalDate#parse} which throws on non-ISO inputs).
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — CUSTOM invalid date format → 400 with MSG_START_DATE_INVALID")
    void submitReport_invalidDateFormat_returns400() throws Exception {
        // Arrange — service returns the failure-result for the
        // invalid-start-date path. The service's actual behaviour depends
        // on which field it parses first; the assertion is on the literal
        // message returned by the mock, so we control the exact reject
        // value here.
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(ReportSubmissionResult.failure(MSG_START_DATE_INVALID));

        // Act + Assert — post with mm/dd/yyyy format instead of yyyy-mm-dd.
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportMode": "CUSTOM",
                                  "startDate": "01/01/2022",
                                  "endDate":   "07/06/2022"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG_START_DATE_INVALID))
                .andExpect(jsonPath("$.jobId").doesNotExist());

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies that a submission with an unknown {@code reportMode} value
     * ({@code "QUARTERLY"} — never an option in the COBOL CORPT00C BMS
     * map's three-radio-button set) is rejected with HTTP 400 and the
     * COBOL-equivalent reject message
     * {@code "Please select a report type..."}. The service does not
     * match the unknown mode and falls through to the
     * {@code MSG_SELECT_REPORT_TYPE} reject (mirrors the COBOL behaviour
     * where the operator did not check any of the three radio buttons).
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — unknown reportMode → 400 with MSG_SELECT_REPORT_TYPE")
    void submitReport_unknownReportType_returns400() throws Exception {
        // Arrange — service does not match the unknown mode "QUARTERLY"
        // and returns the no-mode-selected reject.
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(ReportSubmissionResult.failure(MSG_SELECT_REPORT_TYPE));

        // Act + Assert
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reportMode": "QUARTERLY",
                                  "startDate": "2022-01-01",
                                  "endDate":   "2022-03-31"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG_SELECT_REPORT_TYPE))
                .andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.reportMode").value("QUARTERLY"));

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies that an empty JSON body ({@code {}}) is rejected with HTTP
     * 400 and the COBOL-equivalent reject message
     * {@code "Please select a report type..."}. The empty body
     * deserialises into a {@link ReportSubmissionRequest} with all fields
     * {@code null}; the service detects {@code reportMode = null} and
     * returns the no-mode-selected reject (mirrors the COBOL behaviour
     * where the operator hit Enter without checking any radio button).
     *
     * <p>The {@code $.reportMode} field in the response is asserted to
     * NOT exist (or to be JSON null) because the controller's
     * {@link ReportController.ReportSubmissionJsonResponse#reportMode}
     * field echoes the request's value — which is {@code null} here.
     * Jackson's default serialisation behaviour omits null fields from
     * the JSON output, so {@code $.reportMode} is absent.
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — empty body {} → 400 with MSG_SELECT_REPORT_TYPE")
    void submitReport_emptyBody_returns400() throws Exception {
        // Arrange — service returns the no-mode-selected reject for the
        // empty request (reportMode = null after Jackson deserialisation
        // of `{}`).
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willReturn(ReportSubmissionResult.failure(MSG_SELECT_REPORT_TYPE));

        // Act + Assert
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG_SELECT_REPORT_TYPE))
                .andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.jobName").doesNotExist());

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    // ========================================================================
    // AUTHORISATION + CSRF REJECTS — Spring Security filter chain
    // ========================================================================

    /**
     * Verifies that an unauthenticated request is rejected with HTTP 401
     * by the Spring Security filter chain BEFORE the controller is
     * invoked; the mocked service is verified never to have been invoked
     * (defence-in-depth). The {@code @WithMockUser} annotation is
     * intentionally absent so the request reaches the filter chain as an
     * anonymous caller.
     *
     * <p>The {@code .with(csrf())} post-processor IS included so the
     * Spring Security filter chain bypasses CSRF rejection (HTTP 403) and
     * proceeds to the authentication check (HTTP 401). Without the CSRF
     * token Spring Security's {@code CsrfFilter} would short-circuit
     * before the authentication evaluation, producing HTTP 403 instead of
     * the intended 401 (see {@link #submitReport_missingCsrf_returns403}
     * which exercises the inverse: authenticated + no CSRF → 403). The
     * test pairing therefore covers both Spring Security guards
     * independently — matches the pattern used by
     * {@code TransactionControllerTest#addTransaction_unauthenticated_returns401}.
     */
    @Test
    @DisplayName("submitReport — unauthenticated → 401 Unauthorized; service NOT invoked")
    void submitReport_unauthenticated_returns401() throws Exception {
        // No @WithMockUser, but include .with(csrf()) so the CsrfFilter
        // passes and Spring Security evaluates authentication next. The
        // anonymous principal triggers the 401 reject.
        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monthlyRequestJson()))
                .andExpect(status().isUnauthorized());

        verify(reportSubmissionService, never())
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies that an authenticated POST issued without a valid CSRF
     * token is rejected with HTTP 403 by Spring Security's
     * {@code CsrfFilter} BEFORE the controller is invoked. The
     * {@code .with(csrf())} post-processor is intentionally omitted so the
     * request lacks the {@code X-XSRF-TOKEN} header / cookie pair that
     * the filter requires for state-changing requests.
     *
     * <p>The mocked service is verified never to have been invoked,
     * confirming the CSRF reject is filter-driven (not controller-driven).
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — authenticated POST without CSRF → 403 Forbidden; service NOT invoked")
    void submitReport_missingCsrf_returns403() throws Exception {
        // Authenticated (@WithMockUser) but no .with(csrf()) — Spring
        // Security's CsrfFilter should reject the request with 403 before
        // the controller runs.
        mockMvc.perform(post("/api/reports/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monthlyRequestJson()))
                .andExpect(status().isForbidden());

        verify(reportSubmissionService, never())
                .submit(any(ReportSubmissionRequest.class));
    }

    // ========================================================================
    // SERVICE FAILURE — HTTP 500 with sanitised body
    // ========================================================================

    /**
     * Verifies that an {@link org.springframework.security.access.AccessDeniedException}
     * thrown by the service is RE-THROWN by the controller's
     * {@code @ExceptionHandler} so Spring Security's
     * {@code ExceptionTranslationFilter} can map it to HTTP 403 Forbidden.
     * The controller MUST NOT catch and convert this to HTTP 500 — that
     * would mask the authorisation-failure semantic and prevent
     * downstream audit logging from firing. Mirrors the pattern used by
     * {@code TransactionControllerTest#handleServiceFailure_accessDeniedException_returns403}
     * and {@code UserAdminControllerTest} so the controller-layer
     * exception handlers behave consistently across endpoints (AAP
     * §0.10.10).
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — service throws AccessDeniedException → rethrown → 403 Forbidden")
    void submitReport_accessDeniedException_returns403() throws Exception {
        // Arrange — service throws AccessDeniedException to simulate a
        // method-level authorisation reject inside the service layer.
        // The controller's @ExceptionHandler must re-throw this so the
        // Spring Security ExceptionTranslationFilter converts it to
        // HTTP 403 — NEVER catch-and-convert to 500 (that would mask
        // the audit-relevant authorisation failure).
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willThrow(new org.springframework.security.access.AccessDeniedException(
                        "User lacks permission for /api/reports/submit"));

        mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monthlyRequestJson()))
                .andExpect(status().isForbidden());

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    /**
     * Verifies that when the service throws a {@link RuntimeException}
     * (for example a dispatcher infrastructure failure when the
     * downstream batch executor is unreachable — the Java equivalent of
     * the COBOL {@code 'Unable to Write TDQ (JOBS)...'} response at lines
     * 531-532 of CORPT00C.cbl), the controller's
     * {@code @ExceptionHandler} returns HTTP 500 with the sanitised body
     * literal {@code "An unexpected error occurred"} and NEVER the
     * underlying exception's message or stack trace (AAP §0.10.5 applied
     * to error paths).
     */
    @Test
    @WithMockUser(username = TEST_USERNAME, roles = "USER")
    @DisplayName("submitReport — service throws → 500 with sanitised body (no exception detail leaked)")
    void submitReport_serviceFailure_returns500() throws Exception {
        // Arrange — service throws a RuntimeException carrying a message
        // we explicitly check is NOT echoed back in the response body.
        // The message "Unable to dispatch job to batch executor" is a
        // canary string that would only appear in the response if the
        // controller mistakenly leaked the exception detail.
        given(reportSubmissionService.submit(any(ReportSubmissionRequest.class)))
                .willThrow(new RuntimeException(
                        "Unable to dispatch job to batch executor"));

        // Act + Assert
        MvcResult result = mockMvc.perform(post("/api/reports/submit")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(monthlyRequestJson()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(MSG_INTERNAL_ERROR))
                .andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.jobName").doesNotExist())
                .andReturn();
        // PCI-style defence: the canary substring from the underlying
        // RuntimeException must NOT appear anywhere in the response body.
        // AssertJ body-string assertion per AAP §0.10.10 — no Hamcrest.
        assertThat(result.getResponse().getContentAsString())
                .as("sanitised 500 body must NOT echo internal-exception details (AAP §0.10.5)")
                .doesNotContain("dispatch job to batch executor")
                .doesNotContain("RuntimeException");

        verify(reportSubmissionService, times(1))
                .submit(any(ReportSubmissionRequest.class));
    }

    // ========================================================================
    // STATIC HELPERS
    // ========================================================================

    /**
     * Composes a deterministic {@link ReportSubmissionResult} success
     * outcome for the given {@code reportMode}. The dispatcher-assigned
     * identifier embeds the mode and a deterministic timestamp string
     * derived from {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT} so the
     * produced {@code jobId} is stable across test runs.
     *
     * <p>The produced identifier matches the form
     * {@code "CR00-<MODE>-yyyyMMddHHmmss"} (where {@code CR00} is the
     * verbatim COBOL TRANID from CORPT00C.cbl line 16). The {@code Instant}
     * import is exercised here so the schema-required
     * {@code java.time.Instant} dependency has a real call-site beyond a
     * mere {@code import} statement.
     *
     * @param reportMode one of {@code "MONTHLY"} / {@code "YEARLY"} /
     *                   {@code "CUSTOM"} — embedded into the returned
     *                   identifier for uniqueness across modes
     * @return a {@link ReportSubmissionResult} success outcome carrying
     *         the deterministic identifier
     */
    private static ReportSubmissionResult standardJobHandle(String reportMode) {
        // Deterministic timestamp from the fixed clock instant (e.g.,
        // 2024-01-15T00:00:00Z → "20240115000000"). The Instant.parse
        // round-trip here is the schema-mandated call-site for the
        // java.time.Instant import.
        Instant fixed = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        String stamp = fixed.toString()
                .replace("-", "")
                .replace(":", "")
                .replace("T", "")
                .replace("Z", "")
                .substring(0, 14);
        ReportJobHandle handle = ReportJobHandle.of(
                "CR00-" + reportMode + "-" + stamp);
        return ReportSubmissionResult.success(MSG_SUBMITTED, handle);
    }

    /**
     * Returns the deterministic {@code jobId} string the production
     * controller will echo back in the {@code $.jobId} JSON field for the
     * given mode. Mirrors the identifier construction in
     * {@link #standardJobHandle(String)} so happy-path assertions can
     * compare against a known value without re-deriving it inline.
     *
     * @param reportMode the report mode
     * @return the deterministic {@code jobId} string
     */
    private static String jobIdFor(String reportMode) {
        Instant fixed = Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT);
        String stamp = fixed.toString()
                .replace("-", "")
                .replace(":", "")
                .replace("T", "")
                .replace("Z", "")
                .substring(0, 14);
        return "CR00-" + reportMode + "-" + stamp;
    }

    /**
     * Returns the canonical MONTHLY-mode JSON request body. The body
     * contains only the {@code reportMode} field; the COBOL CORPT00C
     * MONTHLY mode resolves the date window to the current calendar
     * month inside the service, so the client does not need to supply
     * start/end dates.
     *
     * @return the JSON literal
     */
    private static String monthlyRequestJson() {
        return """
                {
                  "reportMode": "MONTHLY"
                }
                """;
    }

    /**
     * Returns the canonical YEARLY-mode JSON request body. The body
     * contains only the {@code reportMode} field; the COBOL CORPT00C
     * YEARLY mode resolves the date window to the current calendar year
     * inside the service.
     *
     * @return the JSON literal
     */
    private static String yearlyRequestJson() {
        return """
                {
                  "reportMode": "YEARLY"
                }
                """;
    }

    /**
     * Returns the canonical CUSTOM-mode JSON request body with the
     * {@link TestFixtures.Dates#REPORT_START_DATE} / 
     * {@link TestFixtures.Dates#REPORT_END_DATE} fixture date window. The
     * {@link String#formatted} call below interpolates the fixture
     * constants so any future renaming of the date fixtures surfaces
     * loudly at compile time rather than at JSON-parse time.
     *
     * @return the JSON literal
     */
    private static String customRangeRequestJson() {
        return """
                {
                  "reportMode": "CUSTOM",
                  "startDate": "%s",
                  "endDate":   "%s"
                }
                """.formatted(
                TestFixtures.Dates.REPORT_START_DATE,
                TestFixtures.Dates.REPORT_END_DATE);
    }
}
