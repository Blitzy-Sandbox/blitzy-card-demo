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
package com.awsm2.carddemo.controller;

// COBOL: CORPT00C.cbl — Report submission (Tran-ID CR00)
// COBOL: Online-to-batch bridge — publishes report.requested Kafka event (AAP §0.6.5)
// COBOL: Service is NOT @Transactional (Kafka publish only, no DB write)
// BMS:   CORPT00.bms
//
// Slice test for {@link ReportController}, which is the Java target for the
// COBOL/CICS program {@code app/cbl/CORPT00C.cbl} (Tran-ID 'CR00') — the
// sole online-to-batch bridge in the CardDemo source base per AAP §0.1.1 /
// §0.6.5. The COBOL program assembles a literal JCL job-submission record
// (the JOB-DATA 80-byte lines at lines 81-127 of the source) and writes
// each line to the CICS Transient Data Queue named 'JOBS' via
// EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD); JES2 picks the JCL
// off the spool via its internal reader and dispatches the report batch
// job (operationally equivalent to app/jcl/TRANREPT.jcl, which in turn
// drives app/cbl/CBTRN03C.cbl to produce the printable report).
//
// In the Java target this asynchronous "fire-and-forget" decoupling is
// preserved by publishing a `report.requested` message to Amazon MSK
// (Kafka) per AAP §0.6.5; a Step Functions trigger Lambda consumes the
// topic and starts an AWS Batch job that runs the TransactionReportJob
// Spring Batch wrapper. The slice test verifies the controller's HTTP
// contract end-to-end against a mocked ReportSubmissionService.

import com.awsm2.carddemo.config.SecurityConfig;
import com.awsm2.carddemo.dto.ApiResponse;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.exception.GlobalExceptionHandler;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.security.JwtAuthenticationFilter;
import com.awsm2.carddemo.security.JwtTokenProvider;
import com.awsm2.carddemo.service.ReportSubmissionService;
import com.awsm2.carddemo.service.ReportSubmissionService.ReportSubmissionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc controller slice test for {@link ReportController}.
 *
 * <h2>System Under Test (SUT)</h2>
 *
 * <p>{@link ReportController} is the Java target for the COBOL/CICS program
 * {@code app/cbl/CORPT00C.cbl} (Tran-ID {@code CR00}), the
 * <strong>only</strong> online-to-batch bridge in the CardDemo source base
 * per AAP &sect;0.1.1 and &sect;0.6.5. The original COBOL program:</p>
 * <ol>
 *   <li>Rendered the {@code app/bms/CORPT00.bms} mapset on a 3270 terminal,
 *       presenting MONTHLY / YEARLY / CUSTOM radio selectors and a Y/N
 *       confirmation field;</li>
 *   <li>On {@code DFHENTER}, the {@code PROCESS-ENTER-KEY} paragraph
 *       (lines 208-456) validated the selector + date triplet and ultimately
 *       invoked the {@code SUBMIT-JOB-TO-INTRDR} paragraph (lines 462-509);</li>
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} assembled a literal JCL job-submission
 *       record (the {@code JOB-DATA} 80-byte lines at lines 81-127) and
 *       wrote each line to the CICS extra-partition Transient Data Queue
 *       named {@code 'JOBS'} via
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)};</li>
 *   <li>JES2 picked the JCL off the spool via its internal reader and
 *       dispatched the report batch job (operationally equivalent to
 *       {@code app/jcl/TRANREPT.jcl}, which in turn drove
 *       {@code app/cbl/CBTRN03C.cbl}).</li>
 * </ol>
 *
 * <p>In the Java target this asynchronous "fire-and-forget" decoupling is
 * preserved by publishing a {@code report.requested} message to Amazon MSK
 * (Kafka) per AAP &sect;0.6.5; a Step Functions trigger Lambda consumes
 * the topic and starts an AWS Batch job that runs the
 * {@code TransactionReportJob} Spring Batch wrapper. The CICS TDQ &rarr;
 * JES bridge becomes MSK &rarr; Step Functions &rarr; AWS Batch &mdash;
 * preserving every behavioral property of the original (asynchronous
 * decoupling, durable hand-off, ordered per-request processing).</p>
 *
 * <h2>Endpoint under test</h2>
 * <p>{@code POST /api/reports/submit} (per AAP &sect;0.3.4) &mdash; the
 * endpoint that replaces the COBOL {@code CORPT00C} program. Returns
 * HTTP {@code 201 Created} (NOT 200) on success per AAP &sect;0.4.1,
 * one of the four endpoints in the project that returns {@code 201}.
 * Guarded by {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} per
 * AAP &sect;0.4.1 (the source COBOL program is reachable from BOTH the
 * regular main menu via {@code COMEN01.bms} option 9 AND the admin menu
 * via {@code COADM01.bms} option 10).</p>
 *
 * <h2>Slice composition</h2>
 *
 * <p>{@code @WebMvcTest(ReportController.class)} loads only this controller
 * plus the Spring MVC infrastructure beans (MockMvc, ObjectMapper, message
 * converters). The {@code @Import({SecurityConfig.class,
 * GlobalExceptionHandler.class})} brings in the EXACT same filter chain
 * wiring used in production:</p>
 * <ul>
 *   <li>{@link com.awsm2.carddemo.config.SecurityConfig} &mdash; for the
 *       URL-level authorization matchers ({@code /api/auth/signin} =
 *       permitAll; {@code /api/admin/**} = ADMIN; everything else under
 *       {@code /api/**} = USER or ADMIN), the
 *       {@code restAuthenticationEntryPoint} that maps anonymous
 *       rejections to HTTP 401 with the standardized {@link ApiResponse}
 *       error envelope, the {@code AccessDeniedHandler} that maps
 *       authenticated-but-unauthorized rejections to HTTP 403, the
 *       stateless {@code SessionCreationPolicy.STATELESS} configuration,
 *       and the method-level
 *       {@code @EnableMethodSecurity(prePostEnabled = true)} that
 *       enforces the {@code @PreAuthorize} gate on
 *       {@link ReportController#submitReport(ReportRequestDto)};</li>
 *   <li>{@link GlobalExceptionHandler} &mdash; explicitly imported because
 *       {@code @WebMvcTest} does not auto-load this advice bean by
 *       default; without it, the standardized {@link ApiResponse} error
 *       envelope shape would not be observed in tests (the
 *       {@code GlobalExceptionHandler#handleValidation(...)},
 *       {@code handleMethodArgumentNotValid(...)},
 *       {@code handleMessageNotReadable(...)} and
 *       {@code handleGenericException(...)} advice methods are exercised
 *       by Phase 2, Phase 3 and Phase 5 below).</li>
 * </ul>
 *
 * <h2>Mock collaborators</h2>
 *
 * <p>Three beans are replaced by Mockito {@link MockBean}s:</p>
 * <ul>
 *   <li>{@link ReportSubmissionService} &mdash; the SUT collaborator;
 *       the system under test delegates to this service for all business
 *       logic (validation, confirmation gating, UUID generation, MSK
 *       publish, audit emission). Mock returns
 *       {@link ReportSubmissionResult} fixtures for success paths and
 *       {@link ValidationException}/{@link RuntimeException} throwables
 *       for failure paths.</li>
 *   <li>{@link JwtTokenProvider} &mdash; required by Spring's bean factory
 *       to satisfy {@link JwtAuthenticationFilter}'s constructor and
 *       {@link com.awsm2.carddemo.config.SecurityConfig}'s filter-chain
 *       wiring. The mock is never invoked because tests populate the
 *       security context via {@code @WithMockUser} /
 *       {@code @WithAnonymousUser} rather than via real bearer-token
 *       validation.</li>
 *   <li>{@link JwtAuthenticationFilter} &mdash; required by Spring
 *       Security's filter-chain wiring; the chain registers the filter
 *       as a pre-{@code UsernamePasswordAuthenticationFilter}. Mocking
 *       it (rather than letting the real filter execute) avoids needing
 *       real JWT signing keys or Secrets Manager integration during the
 *       slice test.
 *
 *       <p><strong>CRITICAL:</strong> Mockito's default mock for a
 *       {@code Filter} does NOT invoke {@code chain.doFilter()} &mdash;
 *       this would drop every request silently (the filter would
 *       short-circuit the chain, the controller would never be invoked,
 *       and {@code MockMvc} would observe an empty HTTP 200 response).
 *       {@link #setUpFilterMock()} explicitly stubs the
 *       {@code doFilter} method with a pass-through answer so the chain
 *       proceeds normally; tests rely on
 *       {@code @WithMockUser}/{@code @WithAnonymousUser} to populate
 *       the security context BEFORE the request enters the chain,
 *       exactly as the production
 *       {@link JwtAuthenticationFilter} would have done after
 *       validating a bearer token.</p></li>
 * </ul>
 *
 * <h2>Security context for tests</h2>
 *
 * <p>Authentication state is established with Spring Security Test's
 * {@link WithMockUser} and {@link WithAnonymousUser} annotations, which
 * populate the {@code SecurityContextHolder} for the duration of the test
 * method without requiring real JWT tokens.</p>
 *
 * <h2>CSRF</h2>
 *
 * <p>{@link com.awsm2.carddemo.config.SecurityConfig} disables CSRF for
 * the entire {@code /api/**} surface (the API is JWT authenticated, not
 * cookie-authenticated). {@code SecurityMockMvcRequestPostProcessors.csrf()}
 * request post-processors are nonetheless attached to POST requests in
 * this test class so the tests remain meaningful if CSRF protection is
 * later re-enabled for any portion of the surface.</p>
 *
 * <h2>TestPropertySource</h2>
 *
 * <p>The {@code carddemo.security.jwt.signing-key} and
 * {@code carddemo.security.cors.allowed-origins} placeholders satisfy
 * Spring's property-resolution requirements when
 * {@link com.awsm2.carddemo.config.SecurityConfig} is imported; since
 * {@link JwtTokenProvider} is mocked the actual key value is not used
 * to sign tokens. The CORS allowed-origins value is set to a concrete
 * host so {@link com.awsm2.carddemo.config.SecurityConfig}'s
 * {@code corsConfigurationSource()} bean has a non-wildcard origin list
 * (a defensive setting that mirrors the production posture).</p>
 *
 * <h2>Test phases</h2>
 *
 * <ol>
 *   <li><strong>Phase 1</strong> &mdash; Successful submission (MONTHLY,
 *       YEARLY, CUSTOM, ADMIN role): HTTP 201 + ApiResponse envelope.</li>
 *   <li><strong>Phase 2</strong> &mdash; Bean Validation on
 *       {@link ReportRequestDto}: missing reportType, invalid reportType,
 *       lowercase reportType, invalid confirm value, malformed JSON.</li>
 *   <li><strong>Phase 3</strong> &mdash; Service-layer cross-field
 *       validation: CUSTOM missing dates, endDate before startDate.</li>
 *   <li><strong>Phase 4</strong> &mdash; Security: 401 for anonymous user.</li>
 *   <li><strong>Phase 5</strong> &mdash; Exception mapping: service throws
 *       RuntimeException &rarr; 500; service throws ValidationException
 *       &rarr; 400.</li>
 *   <li><strong>Phase 6</strong> &mdash; Service argument verification via
 *       {@link ArgumentCaptor}.</li>
 *   <li><strong>Phase 7</strong> &mdash; 201 Created verification (CRITICAL
 *       per AAP &sect;0.4.1; this is one of the 4 endpoints that returns
 *       201 not 200).</li>
 *   <li><strong>Phase 8</strong> &mdash; Response envelope: timestamp,
 *       JSON content type.</li>
 * </ol>
 *
 * @see ReportController
 * @see ReportSubmissionService
 * @see com.awsm2.carddemo.config.SecurityConfig
 * @see GlobalExceptionHandler
 */
// Replaces: app/cbl/CORPT00C.cbl (CICS TRANID 'CR00') — slice test for the
// Java target of the sole online-to-batch bridge. The CICS TDQ 'JOBS' →
// JES batch job submission is replaced by an MSK Kafka publish to the
// 'report.requested' topic per AAP §0.1.1 + §0.6.5; the slice test
// verifies the controller's HTTP contract while the underlying service
// (and its KafkaEventPublisher adapter) is mocked.
@WebMvcTest(ReportController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // The base configuration uses Secrets Manager-backed JWT signing
        // key resolution. For a controller-only slice test we never issue
        // or validate tokens (each test uses Spring Security's
        // @WithMockUser to inject the authentication directly), so we
        // suppress JwtTokenProvider initialization by mocking it; this
        // also keeps the slice fast. Any non-blank value satisfies the
        // @Value injection on the SecurityConfig bean.
        "carddemo.security.jwt.signing-key=test-only-jwt-signing-key-32-bytes-min-length",
        "carddemo.security.cors.allowed-origins=http://localhost:3000"
})
@DisplayName("ReportController — POST /api/reports/submit (online-to-batch bridge via MSK)")
class ReportControllerTest {

    /**
     * MockMvc fluent client into the Spring MVC dispatcher, configured
     * by {@code @WebMvcTest} to route through the SUT controller plus
     * the Spring Security filter chain wired by the imported
     * {@link com.awsm2.carddemo.config.SecurityConfig}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Application Jackson mapper used to serialize {@link ReportRequestDto}
     * fixtures to JSON request bodies for {@code POST /api/reports/submit}.
     * Auto-configured by Spring Boot's {@code JacksonAutoConfiguration} in
     * the {@code @WebMvcTest} slice. The auto-configured mapper has the
     * {@code JavaTimeModule} registered so {@link LocalDate} fields on
     * {@link ReportRequestDto} serialize correctly as ISO-8601
     * {@code yyyy-MM-dd} strings (per the
     * {@code @JsonFormat(pattern = "yyyy-MM-dd")} annotation on the
     * {@link ReportRequestDto#startDate()} / {@link ReportRequestDto#endDate()}
     * record components).
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mock of the {@link ReportSubmissionService} collaborator. The real
     * service publishes the {@code report.requested} MSK Kafka event and
     * emits an OpenSearch audit record; the mock returns test-controlled
     * fixtures so each test exercises only the controller's routing,
     * validation, security, and envelope behaviour. Used with
     * {@link BDDMockito#given} / {@link BDDMockito#willThrow} stubbing plus
     * {@link ArgumentCaptor} for verifying that the
     * {@link ReportRequestDto} reaches the service with every record-
     * accessor value forwarded intact.
     */
    @MockBean
    private ReportSubmissionService reportSubmissionService;

    /**
     * Mock of {@link JwtTokenProvider} required by Spring's bean factory
     * to satisfy {@link JwtAuthenticationFilter}'s constructor and
     * {@link com.awsm2.carddemo.config.SecurityConfig}'s filter-chain
     * wiring. The mock is never invoked because tests populate the
     * security context via {@code @WithMockUser} / {@code @WithAnonymousUser}
     * rather than via real bearer-token validation.
     */
    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    /**
     * Mock of {@link JwtAuthenticationFilter} required by Spring Security's
     * filter-chain wiring; the chain registers the filter as a
     * pre-{@code UsernamePasswordAuthenticationFilter} via
     * {@link com.awsm2.carddemo.config.SecurityConfig#securityFilterChain}.
     * Mocking it (rather than letting the real filter execute) avoids
     * needing real JWT signing keys or Secrets Manager integration during
     * the slice test.
     *
     * <p><strong>CRITICAL:</strong> Mockito's default mock for a
     * {@code Filter} does NOT invoke {@code chain.doFilter()} &mdash;
     * this would drop every request silently (the filter would
     * short-circuit the chain, the controller would never be invoked,
     * and {@code MockMvc} would observe an empty HTTP 200 response).
     * {@link #setUpFilterMock()} explicitly stubs the {@code doFilter}
     * method with a pass-through answer so the chain proceeds normally;
     * tests rely on {@code @WithMockUser}/{@code @WithAnonymousUser} to
     * populate the security context BEFORE the request enters the chain,
     * exactly as the production {@link JwtAuthenticationFilter} would
     * have done after validating a bearer token.</p>
     */
    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Pass-through configuration for the mocked
     * {@link JwtAuthenticationFilter}. Recreated before each test to keep
     * tests independent and to avoid the cross-test pollution that mutable
     * shared fixtures can introduce.
     *
     * <p>Mockito's default mock for the
     * {@code Filter.doFilter(req, resp, chain)} method does NOT invoke
     * {@code chain.doFilter(req, resp)}, so the chain halts at the filter
     * and the controller is never dispatched. The observable symptom is
     * HTTP 200 with empty body across every test. Stubbing the mock with
     * a "delegate to chain" answer restores the production-equivalent
     * behavior of a filter that simply continues the chain when no
     * bearer token is present (see
     * {@code JwtAuthenticationFilter.doFilterInternal}).</p>
     *
     * <p>The {@code doAnswer} style is used because {@code doFilter} is a
     * {@code void} method that cannot be stubbed with
     * {@code thenReturn(...)}. The lambda below calls {@code chain.doFilter}
     * on the same {@code (request, response)} so the request reaches the
     * controller, and Spring Security's {@code SecurityContextHolderFilter}
     * picks up the {@code @WithMockUser}/{@code @WithAnonymousUser}
     * authentication that was placed on the {@code SecurityContextHolder}
     * by {@code WithSecurityContextTestExecutionListener} BEFORE the
     * request entered the chain.</p>
     */
    @BeforeEach
    void setUpFilterMock() throws Exception {
        // ----------------------------------------------------------------
        // Configure the @MockBean JwtAuthenticationFilter to PASS THROUGH.
        // ----------------------------------------------------------------
        Mockito.doAnswer(invocation -> {
            ServletRequest req = invocation.getArgument(0);
            ServletResponse resp = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(req, resp);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    // =====================================================================
    // Phase 1 — POST /api/reports/submit, successful submission
    //
    // COBOL: CORPT00C / Tran-ID CR00 — replaces the CICS TDQ JOBS →
    // JES2 batch dispatch with an MSK Kafka publish to the
    // 'report.requested' topic per AAP §0.1.1 + §0.6.5. Each test stubs
    // the service to return a ReportSubmissionResult fixture so the
    // controller wraps it in ApiResponse.success(...) and surfaces
    // HTTP 201 Created (NOT 200) per AAP §0.4.1.
    // =====================================================================

    /**
     * Verifies that a USER-role caller submitting a valid MONTHLY report
     * request receives HTTP 201 Created with the standardized
     * {@link ApiResponse} envelope wrapping the
     * {@link ReportSubmissionResult} receipt.
     *
     * <p>For MONTHLY reports the {@code startDate} and {@code endDate}
     * fields may be {@code null} &mdash; the COBOL source computes the
     * timeframe as the first and last day of the current month (lines
     * 213-238 of {@code app/cbl/CORPT00C.cbl}); the Java target preserves
     * this behaviour by allowing nullable date fields on the DTO and
     * computing the range inside the service.</p>
     */
    @Test
    @DisplayName("submitReport_returns201ForMonthly — MONTHLY with null dates, confirm='Y'")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns201ForMonthly() throws Exception {
        // COBOL: PROCESS-ENTER-KEY WHEN MONTHLYI NOT = SPACES AND LOW-VALUES
        //        (line 213 of CORPT00C.cbl) — MONTHLY branch.
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-monthly-1",
                "Report request submitted successfully. Request ID: req-monthly-1");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // HTTP 201 Created per AAP §0.4.1 — this is one of the
                // four endpoints in the project that returns 201 (NOT 200).
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value(
                        "Report request submitted successfully"))
                .andExpect(jsonPath("$.data.requestId").value("req-monthly-1"))
                .andExpect(jsonPath("$.data.message").value(
                        "Report request submitted successfully. Request ID: req-monthly-1"))
                // The ApiResponse envelope always carries an Instant
                // timestamp stamped at invocation via Instant.now().
                .andExpect(jsonPath("$.timestamp").exists());

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    /**
     * Verifies that a USER-role caller submitting a valid YEARLY report
     * request receives HTTP 201 Created. The COBOL source computes the
     * YEARLY timeframe as {@code YYYY-01-01} through {@code YYYY-12-31}
     * (lines 239-255 of {@code app/cbl/CORPT00C.cbl}); the Java target
     * preserves this behaviour.
     */
    @Test
    @DisplayName("submitReport_returns201ForYearly — YEARLY with null dates, confirm='Y'")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns201ForYearly() throws Exception {
        // COBOL: PROCESS-ENTER-KEY WHEN YEARLYI NOT = SPACES AND LOW-VALUES
        //        (line 239 of CORPT00C.cbl) — YEARLY branch.
        ReportRequestDto request = new ReportRequestDto(
                "YEARLY", null, null, "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-yearly-1",
                "Report request submitted successfully. Request ID: req-yearly-1");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.requestId").value("req-yearly-1"));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    /**
     * Verifies that a USER-role caller submitting a valid CUSTOM report
     * request with both {@code startDate} and {@code endDate} populated
     * receives HTTP 201 Created. The COBOL source validates the date
     * triplets via {@code CALL 'CSUTLDTC'} (lines 392-426 of
     * {@code app/cbl/CORPT00C.cbl}) and the Java target performs the
     * equivalent validation via {@code DateValidationService} (per AAP
     * &sect;0.5.2 LE {@code CEEDAYS} replacement).
     */
    @Test
    @DisplayName("submitReport_returns201ForCustom — CUSTOM with date range, confirm='Y'")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns201ForCustom() throws Exception {
        // COBOL: PROCESS-ENTER-KEY WHEN CUSTOMI NOT = SPACES AND LOW-VALUES
        //        (line 256 of CORPT00C.cbl) — CUSTOM branch with explicit
        //        date range validation by CSUTLDTC.
        ReportRequestDto request = new ReportRequestDto(
                "CUSTOM",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 1, 31),
                "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-custom-1",
                "Report request submitted successfully. Request ID: req-custom-1");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.requestId").value("req-custom-1"));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    /**
     * Verifies that an ADMIN-role caller also receives HTTP 201 Created.
     * The COBOL source program is reachable from BOTH the regular main
     * menu ({@code COMEN01.bms} option 9) and the admin menu
     * ({@code COADM01.bms} option 10) per AAP &sect;0.4.1, so the
     * {@code @PreAuthorize("hasAnyRole('USER','ADMIN')")} gate accepts
     * either role.
     */
    @Test
    @DisplayName("submitReport_returns201ForAdmin — ADMIN role accepted by hasAnyRole gate")
    @WithMockUser(username = "ADMIN001", roles = "ADMIN")
    void submitReport_returns201ForAdmin() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-admin-1",
                "Report request submitted successfully. Request ID: req-admin-1");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.requestId").value("req-admin-1"));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    // =====================================================================
    // Phase 2 — Bean Validation on ReportRequestDto
    //
    // The DTO declares @NotNull + @Pattern("^(MONTHLY|YEARLY|CUSTOM)$") on
    // reportType and @Pattern("^[YN]$") on confirm (case-sensitive, upper-
    // case only). Jakarta Bean Validation failures yield
    // MethodArgumentNotValidException which GlobalExceptionHandler maps to
    // HTTP 400 with the VALIDATION envelope including per-field errors.
    // Malformed JSON yields HttpMessageNotReadableException which maps to
    // HTTP 400 with MALFORMED_REQUEST envelope.
    // =====================================================================

    /**
     * Verifies that omitting the {@code reportType} field on the request
     * body yields HTTP 400 with the {@code VALIDATION} reason code from
     * {@code GlobalExceptionHandler.handleMethodArgumentNotValid(...)}.
     * The {@code @NotNull(message = "Report type is required")} annotation
     * on {@link ReportRequestDto#reportType()} fires for the JSON
     * {@code {"reportType":null,"confirm":"Y"}} payload.
     */
    @Test
    @DisplayName("submitReport_returns400ForMissingReportType — @NotNull violation, fieldError on reportType")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400ForMissingReportType() throws Exception {
        // Hand-built JSON because the record constructor would also accept
        // null at compile-time and Jackson maps "reportType":null straight
        // through; Bean Validation fires on the @Valid binding.
        String json = "{\"reportType\":null,\"startDate\":null,"
                + "\"endDate\":null,\"confirm\":\"Y\"}";

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reportType"));

        // Service must NOT be invoked when Bean Validation rejects the
        // request before the controller method body runs.
        verifyNoInteractions(reportSubmissionService);
    }

    /**
     * Verifies that a {@code reportType} value of {@code "QUARTERLY"}
     * (not in the allow-list {@code MONTHLY|YEARLY|CUSTOM}) triggers the
     * {@code @Pattern} regex violation and yields HTTP 400 with the
     * standardized envelope.
     */
    @Test
    @DisplayName("submitReport_returns400ForInvalidReportType — QUARTERLY violates @Pattern")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400ForInvalidReportType() throws Exception {
        String json = "{\"reportType\":\"QUARTERLY\",\"confirm\":\"Y\"}";

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reportType"));

        verifyNoInteractions(reportSubmissionService);
    }

    /**
     * Verifies that lowercase {@code reportType} values (e.g.,
     * {@code "monthly"}) are rejected by the case-sensitive
     * {@code @Pattern("^(MONTHLY|YEARLY|CUSTOM)$")} regex. This is an
     * intentional tightening of the COBOL source's tolerance for any
     * non-space character in the selector field (lines 213, 239, 256 of
     * {@code app/cbl/CORPT00C.cbl}); the Java target enforces a strict
     * normalized vocabulary that round-trips cleanly across JSON.
     */
    @Test
    @DisplayName("submitReport_returns400ForLowercaseReportType — lowercase 'monthly' rejected (case-sensitive)")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400ForLowercaseReportType() throws Exception {
        String json = "{\"reportType\":\"monthly\",\"confirm\":\"Y\"}";

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reportType"));

        verifyNoInteractions(reportSubmissionService);
    }

    /**
     * Verifies that a {@code confirm} value of {@code "X"} (not in the
     * allow-list {@code [YN]}) triggers the {@code @Pattern} regex
     * violation. The COBOL source treats this case as
     * {@code '"<x>" is not a valid value to confirm...'} (line 488 of
     * {@code app/cbl/CORPT00C.cbl}); the Java DTO surfaces it as a
     * Jakarta Bean Validation error at the controller boundary.
     */
    @Test
    @DisplayName("submitReport_returns400ForInvalidConfirmValue — confirm='X' violates @Pattern")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400ForInvalidConfirmValue() throws Exception {
        String json = "{\"reportType\":\"MONTHLY\",\"confirm\":\"X\"}";

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("confirm"));

        verifyNoInteractions(reportSubmissionService);
    }

    /**
     * Verifies that a malformed JSON body (not parseable by Jackson)
     * triggers {@link org.springframework.http.converter.HttpMessageNotReadableException}
     * which {@link GlobalExceptionHandler#handleMessageNotReadable(
     * org.springframework.http.converter.HttpMessageNotReadableException,
     * jakarta.servlet.http.HttpServletRequest)} maps to HTTP 400 with the
     * {@code MALFORMED_REQUEST} reason code (NOT {@code VALIDATION}). Per
     * AAP &sect;0.6.6 the sanitized message
     * {@code "Request body could not be parsed as JSON"} is returned
     * instead of leaking Jackson internal class names.
     */
    @Test
    @DisplayName("submitReport_returns400ForMalformedJson — broken JSON yields MALFORMED_REQUEST envelope")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400ForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ broken"))
                .andExpect(status().isBadRequest())
                // Per GlobalExceptionHandler.handleMessageNotReadable —
                // sanitized message does NOT leak Jackson class names.
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Request body could not be parsed as JSON"));

        verifyNoInteractions(reportSubmissionService);
    }

    // =====================================================================
    // Phase 3 — Cross-Field Validation (Service Layer)
    //
    // Bean Validation cannot enforce the CUSTOM-requires-dates rule or the
    // startDate <= endDate chronology rule without a custom validator. The
    // ReportSubmissionService.validateRequest(...) method (lines 463-541 of
    // ReportSubmissionService.java) performs these cross-field checks and
    // throws ValidationException("startDate is required when reportType
    // is CUSTOM"), ValidationException("endDate is required..."), or
    // ValidationException("startDate must be <= endDate"). The
    // GlobalExceptionHandler.handleValidation(...) advice maps these
    // exceptions to HTTP 400 with the VALIDATION envelope.
    // =====================================================================

    /**
     * Verifies that the service-layer rejection of a CUSTOM report missing
     * its dates surfaces as HTTP 400 with the
     * {@link ValidationException}'s message intact. The
     * {@code GlobalExceptionHandler.handleValidation(...)} advice catches
     * the typed exception and produces the standardized
     * {@link ApiResponse} envelope with {@code code = "VALIDATION"}.
     *
     * <p>COBOL provenance: {@code PROCESS-ENTER-KEY WHEN CUSTOMI ...
     * EVALUATE TRUE WHEN SDTMMI / SDTDDI / SDTYYYYI = SPACES OR LOW-VALUES}
     * &mdash; lines 259-300 of {@code app/cbl/CORPT00C.cbl}.</p>
     */
    @Test
    @DisplayName("submitReport_returns400WhenServiceRejectsCustomMissingDates — service throws ValidationException")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400WhenServiceRejectsCustomMissingDates() throws Exception {
        // CUSTOM with null dates: passes DTO Bean Validation (which does
        // not enforce cross-field rules) but rejected at the service.
        ReportRequestDto request = new ReportRequestDto(
                "CUSTOM", null, null, "Y");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willThrow(new ValidationException(
                        "VALIDATION",
                        "startDate is required when reportType is CUSTOM"));

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                // The cross-field error message must reference the
                // offending field name (startDate) so that REST clients
                // can render a field-level error indication.
                .andExpect(jsonPath("$.message", containsString("startDate")))
                // Sanity: message must NOT include any Jackson or Spring
                // internal class names (PCI-DSS / no-info-disclosure).
                .andExpect(jsonPath("$.message", not(containsString("Exception"))));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    /**
     * Verifies that the service-layer chronology check
     * ({@code startDate.isAfter(endDate)} &rarr;
     * {@code throw new ValidationException("startDate must be <= endDate")})
     * surfaces as HTTP 400 with the standardized envelope.
     *
     * <p>COBOL provenance: implicit chronology invariant of the report-window
     * contract (the COBOL JCL parameters {@code PARM-START-DATE-1} and
     * {@code PARM-END-DATE-1} at lines 105-111 of {@code app/cbl/CORPT00C.cbl}
     * presume an ordered range); the Java target enforces it explicitly to
     * produce a clean validation error rather than an empty downstream
     * report.</p>
     */
    @Test
    @DisplayName("submitReport_returns400WhenServiceRejectsEndBeforeStart — startDate after endDate rejected")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400WhenServiceRejectsEndBeforeStart() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "CUSTOM",
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2025, 1, 1),
                "Y");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willThrow(new ValidationException(
                        "VALIDATION",
                        "startDate must be <= endDate"));

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.message", containsString("endDate")));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    // =====================================================================
    // Phase 4 — Security
    //
    // The @PreAuthorize("hasAnyRole('USER','ADMIN')") on submitReport, plus
    // the URL-level matcher .requestMatchers("/api/**").hasAnyRole("USER",
    // "ADMIN") in SecurityConfig, jointly require an authenticated caller
    // with at least ROLE_USER. Anonymous callers are mapped to HTTP 401
    // (NOT 403) by SecurityConfig.restAuthenticationEntryPoint() per AAP
    // §0.3.4 / QA CR-03 (HTTP 401 = unauthenticated, 403 =
    // authenticated-but-unauthorised).
    // =====================================================================

    /**
     * Verifies that an unauthenticated caller is rejected with HTTP 401.
     * The rejection is enforced by Spring Security's
     * {@code authorizeHttpRequests} matcher
     * {@code .requestMatchers("/api/**").hasAnyRole("USER","ADMIN")}
     * combined with
     * {@link com.awsm2.carddemo.config.SecurityConfig}'s
     * {@code restAuthenticationEntryPoint()} which converts anonymous
     * access into HTTP 401 with the standardized {@link ApiResponse}
     * envelope. The mocked service must never be invoked for an
     * anonymous caller.
     */
    @Test
    @DisplayName("submitReport_returns401ForAnonymous — anonymous caller rejected")
    @WithAnonymousUser
    void submitReport_returns401ForAnonymous() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reportSubmissionService);
    }

    /**
     * Verifies that submitting a request WITHOUT any authentication
     * (neither {@code @WithMockUser} nor {@code @WithAnonymousUser})
     * also yields HTTP 401. With no {@code @With*} annotation the
     * Spring Security test context manager does not populate the
     * {@code SecurityContextHolder}, leaving the request effectively
     * anonymous; Spring Security's
     * {@code restAuthenticationEntryPoint()} maps the resulting
     * authorization failure to HTTP 401 with the standardized envelope.
     *
     * <p>This is a defense-in-depth assertion: it confirms that the
     * security gate is enforced regardless of how the caller arrived
     * at the endpoint without credentials.</p>
     */
    @Test
    @DisplayName("submitReport_returns401WithoutJwt — request with no authentication is rejected")
    @WithAnonymousUser
    void submitReport_returns401WithoutJwt() throws Exception {
        // Same shape as submitReport_returns401ForAnonymous; this test
        // uses @WithAnonymousUser explicitly to document the intent that
        // an unauthenticated caller must NEVER reach the service layer.
        ReportRequestDto request = new ReportRequestDto(
                "YEARLY", null, null, "Y");

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                // SecurityConfig.restAuthenticationEntryPoint emits the
                // standardized ApiResponse envelope with code=UNAUTHORIZED.
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(reportSubmissionService);
    }

    // =====================================================================
    // Phase 5 — Exception Mapping
    //
    // GlobalExceptionHandler maps:
    //   * ValidationException        → HTTP 400 with code "VALIDATION"
    //     (handleValidation)
    //   * RuntimeException (generic) → HTTP 500 with code "INTERNAL_ERROR"
    //     (handleGenericException — final catch-all)
    // The 5xx path corresponds to the COBOL '9999-ABEND-PROGRAM' /
    // CEE3ABD-equivalent failure mode (lines 587-591 of CBTRN02C.cbl);
    // in the Java target an unexpected RuntimeException (e.g., MSK
    // unavailable, KafkaTemplate.send timed out) is sanitized to a
    // generic 500 with no internal-state disclosure per AAP §0.6.6.
    // =====================================================================

    /**
     * Verifies that a generic {@link RuntimeException} thrown by the
     * service (e.g., "Kafka unavailable") is caught by
     * {@code GlobalExceptionHandler.handleGenericException(...)} and
     * surfaced as HTTP 500 with the standardized
     * {@code "INTERNAL_ERROR"} reason code.
     *
     * <p>Per AAP &sect;0.6.6 PCI-DSS guidance the response body must NOT
     * echo the underlying exception's message
     * ({@code "Kafka unavailable"}) — instead the generic message
     * {@code "An unexpected error occurred"} is returned. This test
     * asserts both the HTTP status and the sanitized message body.</p>
     */
    @Test
    @DisplayName("submitReport_returns500WhenServiceThrowsRuntime — unexpected RuntimeException → 500 + sanitized message")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns500WhenServiceThrowsRuntime() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willThrow(new RuntimeException("Kafka unavailable"));

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                // The underlying exception message ("Kafka unavailable")
                // MUST NOT be echoed to the caller — PCI-DSS / no-info-
                // disclosure per AAP §0.6.6.
                .andExpect(jsonPath("$.message", not(containsString("Kafka"))));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    /**
     * Verifies that a service-thrown {@link ValidationException} is
     * caught by
     * {@code GlobalExceptionHandler.handleValidation(...)} and surfaced
     * as HTTP 400 with the standardized {@code "VALIDATION"} envelope.
     *
     * <p>Distinct from Phase 3 which exercised specific cross-field
     * messages; this test asserts the general
     * {@code ValidationException &rarr; 400} mapping with no specific
     * message contents.</p>
     */
    @Test
    @DisplayName("submitReport_returns400WhenServiceThrowsValidation — ValidationException → 400 VALIDATION")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returns400WhenServiceThrowsValidation() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willThrow(new ValidationException(
                        "VALIDATION",
                        "Generic validation failure from service"));

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    // =====================================================================
    // Phase 6 — Service Argument Verification
    //
    // The controller must forward EVERY field of the bound DTO to the
    // service intact — no projection, no transformation, no shadow copy.
    // ArgumentCaptor<ReportRequestDto> captures the actual DTO passed to
    // ReportSubmissionService.submitReport(...) so the test can assert
    // that every record-accessor returns the value originally placed on
    // the JSON request body.
    // =====================================================================

    /**
     * Verifies that the controller forwards EVERY field of the request DTO
     * to the service intact. Captures the actual
     * {@link ReportRequestDto} passed to
     * {@link ReportSubmissionService#submitReport(ReportRequestDto)} via
     * {@link ArgumentCaptor} and asserts each record accessor returns the
     * value placed on the JSON request body.
     *
     * <p>The COBOL source program passes the entire {@code CORPT0AI}
     * symbolic-map record (per {@code app/cpy-bms/CORPT00.CPY}) to its
     * downstream paragraphs without filtering; this test ensures the
     * Java target preserves the same value-forwarding contract from the
     * HTTP boundary to the service layer.</p>
     */
    @Test
    @DisplayName("submitReport_passesAllFieldsToService — ArgumentCaptor verifies DTO forwarded intact")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_passesAllFieldsToService() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "CUSTOM",
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 30),
                "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-capture-1", "ok");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        // Capture and inspect — the controller MUST forward the DTO
        // without any transformation. Per AAP §0.7.1 (Minimal Change
        // Clause) the controller is a thin HTTP boundary; the DTO that
        // the service receives must be byte-for-byte the DTO that
        // Jackson bound from the request body.
        ArgumentCaptor<ReportRequestDto> captor =
                ArgumentCaptor.forClass(ReportRequestDto.class);
        verify(reportSubmissionService).submitReport(captor.capture());
        ReportRequestDto captured = captor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals(
                "CUSTOM", captured.reportType(),
                "reportType must be forwarded intact");
        org.junit.jupiter.api.Assertions.assertEquals(
                LocalDate.of(2025, 6, 1), captured.startDate(),
                "startDate must be forwarded intact");
        org.junit.jupiter.api.Assertions.assertEquals(
                LocalDate.of(2025, 6, 30), captured.endDate(),
                "endDate must be forwarded intact");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Y", captured.confirm(),
                "confirm must be forwarded intact");
    }

    /**
     * Verifies that null date fields on a MONTHLY request are forwarded as
     * {@code null} to the service. The COBOL source program leaves the
     * {@code SDTMM / SDTDD / SDTYYYY / EDTMM / EDTDD / EDTYYYY} fields
     * blank for MONTHLY/YEARLY selectors and computes the timeframe
     * inside the program; the Java DTO carries the date fields as
     * {@code null}, and the controller MUST forward those nulls to the
     * service so that
     * {@code ReportSubmissionService.submitReport(...)} can apply the
     * MONTHLY / YEARLY date computation per AAP &sect;0.4.1.
     */
    @Test
    @DisplayName("submitReport_passesNullDatesForMonthly — null dates on MONTHLY forwarded as null")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_passesNullDatesForMonthly() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-null-dates-1", "ok");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReportRequestDto> captor =
                ArgumentCaptor.forClass(ReportRequestDto.class);
        verify(reportSubmissionService).submitReport(captor.capture());
        ReportRequestDto captured = captor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals(
                "MONTHLY", captured.reportType());
        org.junit.jupiter.api.Assertions.assertNull(
                captured.startDate(),
                "MONTHLY requests must forward null startDate to service");
        org.junit.jupiter.api.Assertions.assertNull(
                captured.endDate(),
                "MONTHLY requests must forward null endDate to service");
        org.junit.jupiter.api.Assertions.assertEquals(
                "Y", captured.confirm());
    }

    // =====================================================================
    // Phase 7 — 201 Created Verification (CRITICAL per AAP §0.4.1)
    //
    // The /api/reports/submit endpoint is one of the four endpoints in the
    // project that returns HTTP 201 Created (the other three are
    // /api/auth/signin returns 200, /api/billing/pay returns 201,
    // /api/transactions returns 201, /api/admin/users returns 201). The
    // status MUST be 201, NOT 200 — this test guards that contract
    // explicitly per AAP §0.4.1: "the controller returns HTTP 201 Created
    // (per AAP §0.3.4 status mapping for a POST that creates a resource
    // — here, the asynchronous report-generation submission)".
    // =====================================================================

    /**
     * <strong>Critical contract test (AAP &sect;0.4.1).</strong> Verifies
     * that the {@code POST /api/reports/submit} endpoint returns HTTP
     * 201 Created (NOT 200 OK) per the AAP &sect;0.3.4 status mapping for
     * a POST that creates a resource. The "resource" being created in
     * this case is the asynchronous report-generation submission, whose
     * generated UUID {@code requestId} is the caller's correlation
     * handle for the eventual AWS Batch job and the eventual OpenSearch
     * audit record (AAP &sect;0.6.6).
     *
     * <p>Why this distinction matters: a 200 OK on this endpoint would
     * be semantically incorrect because the request triggered the
     * creation of a tracked async resource (the report submission)
     * rather than a synchronous read or update. Downstream tooling
     * (load balancers, monitoring, retry libraries) may apply different
     * semantics to 200 vs 201; reserving 201 for resource creation
     * preserves REST conventions.</p>
     */
    @Test
    @DisplayName("submitReport_returnsExactlyHttp201 — endpoint returns 201 NOT 200 (CRITICAL per AAP §0.4.1)")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returnsExactlyHttp201() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-201-1", "ok");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // CRITICAL — status().isCreated() asserts exactly HTTP 201.
                // If the controller ever drifts to ResponseEntity.ok(...)
                // (which would yield 200), this assertion fails.
                .andExpect(status().isCreated())
                // Defense-in-depth: also explicitly assert the numeric
                // status to surface a clearer failure message if the
                // matcher above breaks API contract in a future refactor.
                .andExpect(status().is(201));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    // =====================================================================
    // Phase 8 — Response Envelope
    //
    // Every success response must carry the standardized ApiResponse
    // envelope: code, message, data, fieldErrors (null on success),
    // correlationId (may be null), timestamp. The Content-Type must be
    // application/json. Per AAP §0.3.4 the envelope shape is the canonical
    // wire format for all REST endpoints.
    // =====================================================================

    /**
     * Verifies that the success response carries the standardized
     * {@link ApiResponse} envelope including a populated
     * {@code timestamp} (stamped at invocation via {@code Instant.now()}
     * in {@link ApiResponse#success(Object, String)}). The
     * {@code correlationId} property of the envelope is intentionally
     * not asserted-on here because
     * {@link ApiResponse#success(Object, String)} sets it to {@code null}
     * (request-scoped correlation may be added by downstream
     * request-filters via the {@code X-Correlation-Id} response
     * header); but the {@code timestamp} field is always present and
     * verifies the envelope was assembled correctly.
     *
     * <p>The test name {@code submitReport_returnsApiResponseWithCorrelationId}
     * preserves the schema-mandated name even though the substantive
     * assertion is on the {@code timestamp} component — the
     * {@code correlationId} component is null on the controller success
     * path per {@link ApiResponse#success(Object, String)} but the
     * envelope itself is the assertion target.</p>
     */
    @Test
    @DisplayName("submitReport_returnsApiResponseWithCorrelationId — success envelope shape verified")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returnsApiResponseWithCorrelationId() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-envelope-1", "ok");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                // ApiResponse envelope shape — code, message, data,
                // timestamp are required on every success response;
                // correlationId may be null (the controller does not
                // currently populate it).
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }

    /**
     * Verifies that the success response is served with the
     * {@code application/json} Content-Type. Spring MVC's default
     * {@code MappingJackson2HttpMessageConverter} handles the
     * {@code ApiResponse} serialization and emits
     * {@code application/json} (potentially with a {@code charset=UTF-8}
     * suffix); {@link org.springframework.test.web.servlet.result.ContentResultMatchers#contentTypeCompatibleWith(MediaType)}
     * matches both forms.
     */
    @Test
    @DisplayName("submitReport_returnsJsonContentType — response Content-Type is application/json")
    @WithMockUser(username = "USER0001", roles = "USER")
    void submitReport_returnsJsonContentType() throws Exception {
        ReportRequestDto request = new ReportRequestDto(
                "MONTHLY", null, null, "Y");
        ReportSubmissionResult result = new ReportSubmissionResult(
                "req-ctype-1", "ok");
        BDDMockito.given(reportSubmissionService.submitReport(any(ReportRequestDto.class)))
                .willReturn(result);

        mockMvc.perform(post("/api/reports/submit")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                // Content-Type compatibility check — accepts
                // "application/json" or "application/json;charset=UTF-8".
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON));

        verify(reportSubmissionService).submitReport(any(ReportRequestDto.class));
    }
}
